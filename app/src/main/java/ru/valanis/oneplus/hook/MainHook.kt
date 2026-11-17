package ru.valanis.oneplus.hook

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.PreferenceUtils
import ru.valanis.oneplus.hook.framework.FrameworkHook
import ru.valanis.oneplus.hook.settings.SettingsHook
import ru.valanis.oneplus.hook.systemui.AodHook
import ru.valanis.oneplus.hook.systemui.ShadeHook
import ru.valanis.oneplus.hook.systemui.ShadeHookCos17
import ru.valanis.oneplus.hook.systemui.StatusBarHook
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects the outcome of every hook installation and writes one summary line per process
 * to the LSPosed log. Each hook is installed on its own: a class or method missing on one
 * OS version (OxygenOS / ColorOS 16 vs 17) costs that hook only, and the line names it.
 */
object HookStatus {

    private val lines = ConcurrentLinkedQueue<String>()

    fun run(name: String, block: () -> Unit) {
        try {
            block()
            lines.add("+$name")
        } catch (t: Throwable) {
            lines.add("-$name [${t.javaClass.simpleName}: ${t.message}]")
        }
    }

    fun flush(process: String) {
        val all = generateSequence { lines.poll() }.toList()
        if (all.isEmpty()) return
        XposedBridge.log(
            "OxygenOSMaterialIcons hooks[$process] sdk=${Build.VERSION.SDK_INT} " +
                "build=${Build.DISPLAY}: failed=${all.count { it.startsWith("-") }}/${all.size} " +
                all.sortedBy { it.startsWith("+") }.joinToString(", ")
        )
    }
}

/**
 * OxygenOS Material Icons — LSPosed module entry point.
 *
 * Replaces OxygenOS notification icons with standard Material-style icons:
 *   - Status bar: original monochrome small icons (auto-tinted white/dark).
 *   - Notification shade: colored app icons.
 *   - AOD (Always-on Display): monochrome small icons (white silhouettes).
 *
 * Delegates to:
 *   - [FrameworkHook]: blocks OplusNotificationFixHelper.fixSmallIcon.
 *   - [StatusBarHook]: hooks IconManager.getIconDescriptor for status bar and shade.
 *   - [ShadeHook]: forces colored app icons on notification headers.
 *   - [AodHook]: hooks for SystemUI-side AOD + VariUIEngine + com.oplus.aod process.
 *   - [SettingsHook]: injects module entry and settings screen into system Settings.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "OxygenOSMaterialIcons"
        private val warmedUp = AtomicBoolean(false)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // The only hook every scoped process needs. Everything else is installed per
        // process in handleLoadPackage, so e.g. system_server doesn't pay for SystemUI hooks.
        try { AodHook.hookDisableRamlessFeature() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/ramless: ${t.message}")
        }
    }

    /**
     * Loads the settings and the channel-icon map before the first notification needs them:
     * both are read asynchronously, and an icon resolved before they arrive would be drawn
     * with defaults first and swapped later.
     */
    private fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        Thread {
            try {
                // No application Context exists yet when the hooks are installed
                var i = 0
                while (PreferenceUtils.systemResolver() == null && i++ < 100) Thread.sleep(100)
                PreferenceUtils.warmUp()
                i = 0
                while (!PreferenceUtils.isLoaded() && i++ < 50) Thread.sleep(100)
                if (PreferenceUtils.isNotificationCategoryIconsEnabled()) ChannelIconStore.reload()
            } catch (_: Throwable) {}
        }.apply {
            name = "OxygenMaterialWarmUp"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                FrameworkHook.hook(lpparam)
                warmUp()
                HookStatus.flush("system_server")
            }
            "com.android.systemui" -> {
                HookStatus.run("StatusBarNotification.getNotification") { AodHook.hookSbnTracking() }
                HookStatus.run("IconManager.getIconDescriptor") { StatusBarHook.hook(lpparam) }
                HookStatus.run("ShadeHook") { ShadeHook.hook(lpparam) }
                // COS 17 only: on 16 the class of that name has other methods and stays untouched
                if (Build.VERSION.SDK_INT >= 37) {
                    HookStatus.run("cos17 row icon provider") { ShadeHookCos17.hook(lpparam) }
                }
                AodHook.hookSystemUIAod(lpparam)
                HookStatus.run("engine class loader") { AodHook.hookEngineLoader() }
                warmUp()
                HookStatus.flush("systemui")
            }
            "com.android.settings",
            "com.oplus.notificationmanager",
                -> {
                    if (lpparam.packageName == "com.oplus.notificationmanager") {
                        Log.i(
                            TAG,
                            "handleLoadPackage notificationmanager pid=${Process.myPid()}"
                        )
                    }
                    SettingsHook.hook(lpparam)
                }
            "com.oplus.aod" -> {
                AodHook.hookAodProcess(lpparam)
                HookStatus.run("StatusBarNotification.getNotification") { AodHook.hookSbnTracking() }
                HookStatus.run("engine class loader") { AodHook.hookEngineLoader() }
                // The package can also be loaded into another process (e.g. SystemUI)
                if (AodHook.isAodProcess) AodHook.hookAodProcessFramework()
                warmUp()
                HookStatus.flush("aod")
            }
        }
    }
}
