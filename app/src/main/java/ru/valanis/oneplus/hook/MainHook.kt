package ru.valanis.oneplus.hook

import android.annotation.SuppressLint
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.framework.FrameworkHook
import ru.valanis.oneplus.hook.settings.SettingsHook
import ru.valanis.oneplus.hook.systemui.AodHook
import ru.valanis.oneplus.hook.systemui.ShadeHook
import ru.valanis.oneplus.hook.systemui.StatusBarHook

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
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        try { AodHook.hookDisableRamlessFeature() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/ramless: ${t.message}")
        }
        try { AodHook.hookZygoteSbnMethods() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/SBN: ${t.message}")
        }
        try { AodHook.hookZygotePmGetApplicationIcon() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/PM: ${t.message}")
        }
        try { AodHook.hookZygoteNotificationGetLargeIcon() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/LargeIcon: ${t.message}")
        }
        try { AodHook.hookZygoteDexClassLoaderInit() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/DCL: ${t.message}")
        }
        XposedBridge.log("$TAG: initZygote complete")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> FrameworkHook.hook(lpparam)
            "com.android.systemui" -> {
                try {
                    StatusBarHook.hook(lpparam)
                    ShadeHook.hook(lpparam)
                    AodHook.hookSystemUIAod(lpparam)
                    try { AodHook.hookEngineLoaderInProcess() } catch (_: Throwable) {}
                    XposedBridge.log("$TAG: SystemUI hook OK")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG SystemUI: ${t.message}")
                }
            }
            "com.android.settings" -> SettingsHook.hook(lpparam)
            "com.oplus.aod" -> AodHook.hookAodProcess(lpparam)
        }
    }
}
