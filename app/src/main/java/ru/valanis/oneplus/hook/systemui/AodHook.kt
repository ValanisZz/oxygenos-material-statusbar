package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.HookStatus
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.IconUtils.toBitmap
import ru.valanis.oneplus.hook.core.PreferenceUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")

@SuppressLint("PrivateApi")
object AodHook {

    private const val TAG = "OxygenOSMaterialIcons"
    private val engineHooked = AtomicBoolean(false)
    private val engineLoaderHooked = AtomicBoolean(false)
    private val ramlessHooked = AtomicBoolean(false)
    private val sbnHooked = AtomicBoolean(false)
    private val aodFrameworkHooked = AtomicBoolean(false)

    val isAodProcess: Boolean by lazy {
        val name = detectProcessName()
        name.startsWith("com.oplus.aod")
    }

    private fun detectProcessName(): String {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val name = at.getDeclaredMethod("currentProcessName").invoke(null) as? String
            if (!name.isNullOrEmpty()) return name
        } catch (_: Throwable) {}
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getDeclaredMethod("currentApplication").invoke(null)
            if (app != null) {
                val pname = app.javaClass.getMethod("getProcessName").invoke(app) as? String
                if (!pname.isNullOrEmpty()) return pname
            }
        } catch (_: Throwable) {}
        return try {
            File("/proc/self/cmdline").readText().trim('\u0000')
        } catch (_: Throwable) { "" }
    }

    private val currentApplicationMethod by lazy {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication")
    }

    private fun appContext(): Context? =
        try { currentApplicationMethod.invoke(null) as? Context } catch (_: Throwable) { null }

    // ═══════════════════════════════════════════════
    //  Process-wide framework hooks
    // ═══════════════════════════════════════════════

    fun hookDisableRamlessFeature() {
        val cfgClass = Class.forName("com.oplus.content.OplusFeatureConfigManager")
        if (!ramlessHooked.compareAndSet(false, true)) return
        try {
            XposedBridge.hookAllMethods(cfgClass, "hasFeature", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // hasFeature is extremely hot — compare the string before touching prefs
                    val feature = param.args.getOrNull(0) as? String ?: return
                    if (feature != "oplus.software.display.aod_ramless_support") return
                    if (!PreferenceUtils.isAodEnabled()) return
                    param.result = false
                }
            })
        } catch (t: Throwable) {
            ramlessHooked.set(false)
            throw t
        }
    }

    private fun originalSmallIcon(notification: Notification): Icon? {
        val saved = try {
            notification.extras?.getParcelable(IconUtils.EXTRAS_ORIGINAL_ICON) as? Icon
        } catch (_: Throwable) { null }
        return saved ?: notification.smallIcon
    }

    /** Icon shown on AOD for one notification: category icon, author icon or the app's own. */
    private fun resolveAodIcon(pkg: String, notification: Notification): Icon? {
        val channelId = try { notification.channelId } catch (_: Throwable) { null }
        if (!channelId.isNullOrEmpty()) {
            val ctx = appContext()
            if (ctx != null) {
                IconUtils.channelOverrideIcon(pkg, channelId, ctx, "aod")?.let { return it }
            }
        }
        return IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification)
    }

    // ── Per-notification work, done once per Notification object ─────────────

    // unpatched = the small icon we replaced with a category icon (null = not patched)
    private class Seen(val gen: Int, val unpatched: Icon?)

    private val seen = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Notification, Seen>())
    @Volatile private var lastSeen: java.lang.ref.WeakReference<Notification>? = null
    @Volatile private var lastSeenGen = -1

    private class Tracked(val postTime: Long, val summary: Boolean, val icon: Icon)

    // pkg -> sbn key -> icon. The AOD engine asks for one icon per package: the most
    // recently posted notification wins, whatever order the notifications are touched in
    private val trackedByPkg = ConcurrentHashMap<String, ConcurrentHashMap<String, Tracked>>()
    private const val MAX_TRACKED_PER_PKG = 32

    private fun publish(pkg: String, byKey: Map<String, Tracked>) {
        var best: Tracked? = null
        for (t in byKey.values) {
            val b = best
            // A group summary only counts while it is the package's sole notification
            if (b == null || (b.summary && !t.summary) ||
                (b.summary == t.summary && t.postTime >= b.postTime)
            ) best = t
        }
        // Nothing left: keep the last icon, the engine may still draw the outgoing one
        best?.let { IconUtils.iconCache[pkg] = it.icon }
        if (PreferenceUtils.isDebugLog()) {
            XposedBridge.log(
                "$TAG aod pkg=$pkg icon=${best?.icon} of " +
                    byKey.entries.joinToString { "${it.key}@${it.value.postTime}${if (it.value.summary) "(summary)" else ""}" }
            )
        }
    }

    private fun track(sbn: StatusBarNotification, pkg: String, notification: Notification) {
        val icon = resolveAodIcon(pkg, notification) ?: return
        val key = sbn.key ?: return
        val byKey = trackedByPkg.getOrPut(pkg) { ConcurrentHashMap() }
        // Removals normally prune this; the cap only guards against a missed one
        if (byKey.size >= MAX_TRACKED_PER_PKG && !byKey.containsKey(key)) byKey.clear()
        byKey[key] = Tracked(
            sbn.postTime, (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0, icon
        )
        publish(pkg, byKey)
    }

    private fun untrack(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val byKey = trackedByPkg[pkg] ?: return
        if (byKey.remove(sbn.key) != null) publish(pkg, byKey)
    }

    /** [sbns] is the complete active list of a listener: forget everything else. */
    private fun retainActive(sbns: Array<*>) {
        val active = HashSet<String>()
        for (s in sbns) {
            val sbn = s as? StatusBarNotification ?: continue
            val key = sbn.key ?: continue
            active.add(key)
            // Reading the notification runs onNotificationSeen (category icon patched in)
            val notification = noteNotification(sbn) ?: continue
            val pkg = sbn.packageName ?: continue
            // It may have been pruned by another listener's shorter list
            if (trackedByPkg[pkg]?.containsKey(key) != true && PreferenceUtils.isModuleEnabled()) {
                track(sbn, pkg, notification)
            }
        }
        for ((pkg, byKey) in trackedByPkg) {
            if (byKey.keys.retainAll(active)) publish(pkg, byKey)
        }
    }

    /**
     * First sight of a Notification object in this process (it is a fresh object for every
     * post/update): put the category icon into mSmallIcon, so every renderer that reads the
     * notification draws it from the first frame, and record the package's AOD icon.
     * Redone when the settings or the channel map change ([IconUtils.configGeneration]).
     */
    private fun onNotificationSeen(sbn: StatusBarNotification, notification: Notification) {
        val gen = IconUtils.configGeneration.get()
        if (gen == lastSeenGen && lastSeen?.get() === notification) return
        val prev = seen[notification]
        if (prev != null && prev.gen == gen) {
            lastSeen = java.lang.ref.WeakReference(notification)
            lastSeenGen = gen
            return
        }
        val pkg = sbn.packageName ?: return
        val enabled = PreferenceUtils.isModuleEnabled()
        val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
        val base = prev?.unpatched ?: notification.smallIcon
        var unpatched: Icon? = null

        val override = if (enabled && channelId.isNotEmpty()) {
            appContext()?.let { IconUtils.channelOverrideIcon(pkg, channelId, it, "aod") }
        } else null
        if (override != null && base != null) {
            try {
                val extras = notification.extras
                // The framework hook already stored the app's icon; don't replace it with
                // whatever the OS turned the small icon into afterwards
                if (extras != null && !extras.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON)) {
                    extras.putParcelable(IconUtils.EXTRAS_ORIGINAL_ICON, base)
                }
                // A private copy: the icon object identifies this notification at bind time
                XposedHelpers.setObjectField(
                    notification, "mSmallIcon", IconUtils.copyChannelOverrideIcon(override)
                )
                unpatched = base
            } catch (_: Throwable) {}
        } else if (prev?.unpatched != null) {
            // The category icon was removed or switched off: put the icon back
            try { XposedHelpers.setObjectField(notification, "mSmallIcon", prev.unpatched) } catch (_: Throwable) {}
        }
        notification.smallIcon?.let { IconUtils.setIconOwner(it, pkg, channelId) }

        if (enabled) track(sbn, pkg, notification)
        seen[notification] = Seen(gen, unpatched)
    }

    /**
     * Reads the notification of [sbn] and makes sure it has been processed. The getter hook
     * below does the same, but the runtime may have inlined the getter into compiled callers,
     * so every hook of this module that needs the notification goes through here.
     */
    fun noteNotification(sbn: StatusBarNotification): Notification? {
        val notification = sbn.notification ?: return null
        try { onNotificationSeen(sbn, notification) } catch (_: Throwable) {}
        return notification
    }

    /**
     * Hooks for the processes that hold or draw notifications (SystemUI, AOD). They are not
     * installed anywhere else: system_server calls these SBN getters constantly.
     */
    fun hookSbnTracking() {
        if (!sbnHooked.compareAndSet(false, true)) return
        XposedBridge.hookAllMethods(StatusBarNotification::class.java, "getNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val notification = param.result as? Notification ?: return
                    onNotificationSeen(param.thisObject as StatusBarNotification, notification)
                } catch (_: Throwable) {}
            }
        })
        // Base implementations of every onNotificationRemoved overload: the listener's
        // handler enters through the widest one, whichever the subclass overrides
        try {
            XposedBridge.hookAllMethods(
                NotificationListenerService::class.java, "onNotificationRemoved",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            (param.args.getOrNull(0) as? StatusBarNotification)?.let { untrack(it) }
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG onNotificationRemoved hook: ${t.message}")
        }
    }

    /** AOD process only: the engine there asks PackageManager / large icons for its pictures. */
    fun hookAodProcessFramework() {
        if (!aodFrameworkHooked.compareAndSet(false, true)) return
        HookStatus.run("aod ApplicationPackageManager.getApplicationIcon") { hookPmGetApplicationIcon() }
        HookStatus.run("aod Notification.getLargeIcon") { hookNotificationGetLargeIcon() }
    }

    private fun hookPmGetApplicationIcon() {
        val pmClass = Class.forName("android.app.ApplicationPackageManager")
        XposedBridge.hookAllMethods(pmClass, "getApplicationIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isAodEnabled()) return
                try {
                    val pkg: String = when (val arg = param.args.getOrNull(0)) {
                        is String -> arg
                        is ApplicationInfo -> arg.packageName
                        else -> return
                    }
                    val cachedIcon = IconUtils.iconCache[pkg]
                    if (cachedIcon != null) {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                        val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                        val drawable = cachedIcon.loadDrawable(pkgContext)
                        if (drawable != null) {
                            param.result = drawable
                            return
                        }
                    }
                    val result = param.result as? Drawable ?: return
                    param.result = IconUtils.toWhiteSilhouette(result)
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookNotificationGetLargeIcon() {
        XposedBridge.hookAllMethods(Notification::class.java, "getLargeIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isAodEnabled()) return
                try {
                    val notification = param.thisObject as Notification
                    val smallIcon = originalSmallIcon(notification) ?: return
                    param.result = smallIcon
                } catch (_: Throwable) {}
            }
        })
    }

    /** The AOD engine is a plugin dex: hook its classes when its class loader is created. */
    fun hookEngineLoader() {
        if (!engineLoaderHooked.compareAndSet(false, true)) return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dexPath = param.args.getOrNull(0) as? String ?: return
                if (!dexPath.contains("uiengine", ignoreCase = true)) return
                if (!engineHooked.compareAndSet(false, true)) return
                val cl = param.thisObject as ClassLoader
                hookAllEngineClasses(cl)
            }
        }
        XposedBridge.hookAllConstructors(DexClassLoader::class.java, hook)
        try {
            XposedBridge.hookAllConstructors(BaseDexClassLoader::class.java, hook)
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════
    //  SystemUI-side AOD hooks
    // ═══════════════════════════════════════════════

    fun hookSystemUIAod(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookAodCurvedDisplaySmallIcon(lpparam)
        hookAodNotificationLayoutSmallIcon(lpparam)
        hookAodNotificationListenerGetActiveNotifications(lpparam)
    }

    // ═══════════════════════════════════════════════
    //  com.oplus.aod process hooks
    // ═══════════════════════════════════════════════

    fun hookAodProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.i(TAG, "hookAodProcess: pid=${android.os.Process.myPid()} pkg=${lpparam.packageName}")
        // Normally installed from initZygote already; this is the fallback
        HookStatus.run("aod OplusFeatureConfigManager.hasFeature") { hookDisableRamlessFeature() }
        hookUIEngineManagerInit(lpparam)
    }

    // ═══════════════════════════════════════════════
    //  Private implementation
    // ═══════════════════════════════════════════════

    private fun hookAodNotificationLayoutSmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookStatus.run("AOD NotificationLayout.onReceiveNotification") {
            val layoutClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.aodclock.off.notification.NotificationLayout",
                lpparam.classLoader
            )
            // onReceiveNotification(SBN[] allActive, SBN incoming, boolean flag)
            val hooked = XposedBridge.hookAllMethods(layoutClass, "onReceiveNotification", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // Reading the notification runs onNotificationSeen (category icon
                        // patched in) before the layout renders the incoming SBN
                        (param.args.getOrNull(1) as? StatusBarNotification)?.let { noteNotification(it) }
                    } catch (_: Throwable) {}
                }
            })
            if (hooked.isEmpty()) throw NoSuchMethodError("onReceiveNotification")
        }
    }

    private fun hookAodCurvedDisplaySmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CurvedDisplayManager posts ShowCurvedNotification (a Runnable) to a Handler.
        // When run() fires, mNotification holds the SBN to display on the curved edge.
        HookStatus.run("AOD CurvedDisplayManager.ShowCurvedNotification.run") {
            val runnableClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.common.CurvedDisplayManager\$ShowCurvedNotification",
                lpparam.classLoader
            )
            val hooked = XposedBridge.hookAllMethods(runnableClass, "run", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    try {
                        val sbn = XposedHelpers.getObjectField(param.thisObject, "mNotification")
                            as? StatusBarNotification ?: return
                        val pkg = sbn.packageName
                        val notification = noteNotification(sbn) ?: return
                        // Also patch mIncomingNotiPaint.mDrawable on the curved view
                        val mgr = XposedHelpers.getObjectField(param.thisObject, "instance") ?: return
                        val view = XposedHelpers.getObjectField(mgr, "mCurvedDisplayView") ?: return
                        val paint = XposedHelpers.getObjectField(view, "mIncomingNotiPaint") ?: return
                        val channelId = try { notification.channelId } catch (_: Throwable) { null }
                        if (channelId.isNullOrEmpty()) return
                        val ctx = appContext() ?: return
                        val override = IconUtils.channelOverrideIcon(pkg, channelId, ctx, "aod") ?: return
                        val pkgContext = try { ctx.createPackageContext(pkg, 0) } catch (_: Throwable) { ctx }
                        val iconDrawable = override.loadDrawable(pkgContext) ?: return
                        val monoDrawable = IconUtils.getCachedMonochrome(override, pkg, iconDrawable)
                        val iconRect = try { XposedHelpers.getObjectField(paint, "mIconRect") as? Rect } catch (_: Throwable) { null }
                        if (iconRect != null) monoDrawable.bounds = iconRect
                        XposedHelpers.setObjectField(paint, "mDrawable", monoDrawable)
                    } catch (_: Throwable) {}
                }
            })
            if (hooked.isEmpty()) throw NoSuchMethodError("run")
        }
    }

    /**
     * The AOD listener's complete active list: makes sure every notification on it has been
     * seen (category icon patched in) and drops tracked icons of notifications no longer on it.
     */
    private fun hookAodNotificationListenerGetActiveNotifications(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookStatus.run("AOD AodNotificationListenerService.getActiveNotifications") {
            val serviceClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.common.AodNotificationListenerService",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(serviceClass, "getActiveNotifications", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sbnArray = param.result as? Array<*> ?: return
                    try {
                        // Only the no-arg overload returns the whole list
                        if (param.args.isNullOrEmpty()) retainActive(sbnArray)
                        else for (s in sbnArray) (s as? StatusBarNotification)?.let { noteNotification(it) }
                    } catch (_: Throwable) {}
                }
            })
        }
    }

    private fun hookUIEngineManagerInit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val uiemClass = XposedHelpers.findClass(
                "com.oplus.aod.uiengine.UIEngineManager",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(uiemClass, "init", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val engineCL = XposedHelpers.getObjectField(param.thisObject, "engineClassLoader") as? ClassLoader
                            if (engineCL == null) {
                                XposedBridge.log("$TAG UIEngineManager.init: engineClassLoader field not found")
                                return
                            }
                            if (!engineHooked.compareAndSet(false, true)) {
                                Log.e(TAG, "UIEngineManager.init: engine already hooked, skip")
                                return
                            }
                            Log.e(TAG, "UIEngineManager.init: hooking engine classes via $engineCL")
                            hookAllEngineClasses(engineCL)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG UIEngineManager.init error: ${t.message}")
                        }
                    }
                }
            )
            Log.e(TAG, "hookUIEngineManagerInit: hooked UIEngineManager.init")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookUIEngineManagerInit FAILED: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════
    //  Engine hooks (AOD VariUIEngine)
    // ═══════════════════════════════════════════════

    private fun hookAllEngineClasses(engineCL: ClassLoader) {
        var ok = 0
        try { hookEngineUpdateNotificationIconData(engineCL); ok++ } catch (t: Throwable) {
            XposedBridge.log("$TAG engine hookUpdateNotificationIconData FAILED: ${t.message}")
        }
        try { hookEngineNotificationView(engineCL); ok++ } catch (t: Throwable) {
            XposedBridge.log("$TAG engine hookNotificationView FAILED: ${t.message}")
        }
        try { hookEngineEgCommonHelper(engineCL); ok++ } catch (t: Throwable) {
            XposedBridge.log("$TAG engine hookEgCommonHelper FAILED: ${t.message}")
        }
        try { hookEngineGlideLoader(engineCL); ok++ } catch (t: Throwable) {
            XposedBridge.log("$TAG engine hookGlideLoader FAILED: ${t.message}")
        }
        XposedBridge.log("$TAG hookAllEngineClasses: $ok/4 hooks installed")
    }

    private fun hookEngineUpdateNotificationIconData(engineCL: ClassLoader) {
        val nvClass = engineCL.loadClass("com.oplus.egview.widget.NotificationView")
        XposedBridge.hookAllMethods(nvClass, "updateNotificationIconData", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isAodEnabled()) return
                try {
                    val pkgNames = param.args[0] ?: return
                    val drawables = param.args[1] ?: return
                    val pkgArray = pkgNames as Array<*>
                    val drawableArray = drawables as Array<*>
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return

                    for (i in pkgArray.indices) {
                        val pkg = pkgArray[i] as? String ?: continue
                        if (pkg.isEmpty()) continue

                        val originalDrawable = drawableArray[i] as? Drawable
                        val targetW = originalDrawable?.intrinsicWidth ?: 0
                        val targetH = originalDrawable?.intrinsicHeight ?: 0

                        val cachedIcon = IconUtils.iconCache[pkg]
                        if (cachedIcon != null) {
                            try {
                                val rawDrawable = IconUtils.loadIconWithoutTheme(cachedIcon, pkg, context)
                                if (rawDrawable != null) {
                                    val rawMono = IconUtils.getCachedMonochrome(cachedIcon, pkg, rawDrawable, rawPath = true)
                                    val rawMonoBm = rawMono.toBitmap()
                                    if (!IconUtils.isBadSilhouette(rawMonoBm)) {
                                        val baseKey = IconUtils.getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
                                        val fitted = IconUtils.fitToSize(rawMono, targetW, targetH, context.resources, "${baseKey}_raw")
                                        java.lang.reflect.Array.set(drawables, i, fitted)
                                        continue
                                    }
                                    val baseKey = IconUtils.getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
                                    IconUtils.monochromeCache.remove("${baseKey}_raw")
                                }
                            } catch (_: Throwable) {}

                            try {
                                val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                                val smallDrawable = cachedIcon.loadDrawable(pkgContext)
                                if (smallDrawable != null) {
                                    val mono = IconUtils.getCachedMonochrome(cachedIcon, pkg, smallDrawable)
                                    // No content key -> skip fit caching rather than share one entry per package
                                    val baseKey = IconUtils.getIconCacheKey(cachedIcon, pkg)
                                    val fitted = IconUtils.fitToSize(mono, targetW, targetH, context.resources, baseKey)
                                    java.lang.reflect.Array.set(drawables, i, fitted)
                                    continue
                                }
                            } catch (_: Throwable) {}
                        }

                        if (originalDrawable != null) {
                            val mono = IconUtils.getCachedMonochromeFallback(pkg, originalDrawable)
                            java.lang.reflect.Array.set(drawables, i, mono)
                        }
                    }
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookEngineNotificationView(engineCL: ClassLoader) {
        val nvClass = engineCL.loadClass("com.oplus.egview.widget.NotificationView")
        XposedBridge.hookAllMethods(nvClass, "getPackageDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isAodEnabled()) return
                try {
                    val showList = param.args[0] as? List<*> ?: return
                    val pkg = param.args.getOrNull(1) as? String ?: "?"
                    val sbn = param.args[2] ?: return
                    if (showList.size >= 6) return

                    val notification = (sbn as? StatusBarNotification)?.let { noteNotification(it) } ?: return
                    val smallIcon = resolveAodIcon(pkg, notification) ?: return
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                    val drawable = smallIcon.loadDrawable(context) ?: return
                    val monoDrawable = IconUtils.getCachedMonochrome(smallIcon, pkg, drawable)

                    try {
                        val roundedBitmap = XposedHelpers.callMethod(
                            param.thisObject, "drawableToRoundedBitmap", monoDrawable
                        ) as? android.graphics.Bitmap
                        if (roundedBitmap != null) {
                            param.result = roundedBitmap.toDrawable(context.resources)
                            return
                        }
                    } catch (_: Throwable) {}
                    param.result = monoDrawable
                } catch (_: Throwable) {}
            }
        })
    }

    private fun hookEngineEgCommonHelper(engineCL: ClassLoader) {
        try {
            val helperClass = engineCL.loadClass("com.oplus.egview.util.EgCommonHelper")
            XposedBridge.hookAllMethods(helperClass, "getApplicationIcon", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    try {
                        val context = param.args.getOrNull(0) as? Context ?: return
                        val pkg = param.args.getOrNull(1) as? String ?: return
                        val icon = IconUtils.iconCache[pkg]
                        if (icon != null) {
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val drawable = icon.loadDrawable(pkgContext)
                            if (drawable != null) {
                                param.result = drawable
                                return
                            }
                        }
                        val result = param.result as? Drawable ?: return
                        param.result = IconUtils.toWhiteSilhouette(result)
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    private fun hookEngineGlideLoader(engineCL: ClassLoader) {
        try {
            val loaderClass = engineCL.loadClass("com.oplus.egview.glide.GlideLoaderKt")
            XposedBridge.hookAllMethods(loaderClass, "loadGeneric", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    val generic = param.args.getOrNull(0) as? String ?: return
                    val imageView = param.args.getOrNull(1) as? ImageView ?: return
                    if (!IconUtils.looksLikePackageName(generic)) return
                    val cachedIcon = IconUtils.iconCache[generic] ?: return
                    try {
                        val ctx = imageView.context
                        val pkgContext = try { ctx.createPackageContext(generic, 0) } catch (_: Throwable) { ctx }
                        val drawable = cachedIcon.loadDrawable(pkgContext) ?: return
                        imageView.setImageDrawable(drawable)
                        param.result = null
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }
}
