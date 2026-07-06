package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
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
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.IconUtils.toBitmap
import ru.valanis.oneplus.hook.core.PreferenceUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")

@SuppressLint("PrivateApi")
object AodHook {

    private const val TAG = "OxygenOSMaterialIcons"
    private val engineHooked = AtomicBoolean(false)
    // Set of "pkg/channelId" strings we've already logged a patch failure for (avoid flooding)
    private val loggedPatchFailures = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val isAodProcess: Boolean by lazy {
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

    // ═══════════════════════════════════════════════
    //  Zygote-level hooks (inherited by all processes)
    // ═══════════════════════════════════════════════

    fun hookDisableRamlessFeature() {
        val cfgClass = Class.forName("com.oplus.content.OplusFeatureConfigManager")
        XposedBridge.hookAllMethods(cfgClass, "hasFeature", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isAodEnabled()) return
                val feature = param.args.getOrNull(0) as? String ?: return
                if (feature == "oplus.software.display.aod_ramless_support") {
                    param.result = false
                }
            }
        })
    }

    private fun originalSmallIcon(notification: Notification): Icon? {
        val saved = try {
            notification.extras?.getParcelable(IconUtils.EXTRAS_ORIGINAL_ICON) as? Icon
        } catch (_: Throwable) { null }
        return saved ?: notification.smallIcon
    }

    /**
     * Patches notification.mSmallIcon at the earliest Zygote-level opportunity so that
     * ALL renderers (shade first-draw, AOD, lock-screen) immediately see the channel icon.
     * No-op if already patched or no channel override configured.
     */
    private fun tryEarlyPatch(pkg: String, notification: Notification) {
        if (notification.extras?.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON) == true) {
            // Only skip if the patch actually worked (smallIcon is a bitmap = type 1).
            // If smallIcon is still a resource (type 2), setObjectField failed last time —
            // retry the patch exactly once (EXTRAS may be stale from a previous SystemUI session).
            val currentType = notification.smallIcon?.type ?: -1
            if (currentType == 1) return  // bitmap — patch is in effect
            if (loggedPatchFailures.putIfAbsent("$pkg/retryPatch", true) != null) {
                // Already retried — don't keep removing EXTRAS; iconCache should hold our bitmap.
                return
            }
            Log.e(TAG, "tryEarlyPatch[$pkg]: EXTRAS set but smallIcon.type=$currentType — retrying patch once")
            try { notification.extras?.remove(IconUtils.EXTRAS_ORIGINAL_ICON) } catch (_: Throwable) {}
        }
        val channelId = try { notification.channelId } catch (_: Throwable) { null }
        if (channelId.isNullOrEmpty()) {
            if (loggedPatchFailures.putIfAbsent("$pkg/noChannel", true) == null) {
                Log.e(TAG, "tryEarlyPatch[$pkg]: skipped — channelId=null/empty")
            }
            return
        }
        val path = ru.valanis.oneplus.hook.core.ChannelIconStore.getAodPath(pkg, channelId)
        if (path == null) {
            val storeSize = ru.valanis.oneplus.hook.core.ChannelIconStore.cachedSize()
            val failKey = "$pkg/$channelId"
            if (loggedPatchFailures.putIfAbsent(failKey, true) == null) {
                Log.e(TAG, "tryEarlyPatch[$pkg/$channelId]: path=null storeSize=$storeSize mainPath=${ru.valanis.oneplus.hook.core.ChannelIconStore.getPath(pkg, channelId)}")
            }
            return
        }
        run {
            val failKey = "$pkg/$channelId/found"
            if (loggedPatchFailures.putIfAbsent(failKey, true) == null) {
                Log.e(TAG, "tryEarlyPatch[$pkg/$channelId]: path=$path")
            }
        }
        val context = try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? android.content.Context
        } catch (_: Throwable) { null }
        if (context == null) {
            Log.e(TAG, "tryEarlyPatch[$pkg/$channelId]: no context")
            return
        }
        if (!ru.valanis.oneplus.hook.core.PreferenceUtils.isNotificationCategoryIconsEnabled()) return
        val override = IconUtils.channelOverrideIcon(pkg, channelId, context, "aod")
        if (override == null) {
            Log.e(TAG, "tryEarlyPatch[$pkg/$channelId]: channelOverrideIcon=null path=$path")
            return
        }
        try { notification.extras?.putParcelable(IconUtils.EXTRAS_ORIGINAL_ICON, notification.smallIcon) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(notification, "mSmallIcon", override) } catch (_: Throwable) {}
        IconUtils.clearPackageBitmapCaches(pkg)
        IconUtils.iconCache[pkg] = override
        Log.e(TAG, "tryEarlyPatch[$pkg/$channelId]: PATCHED path=$path")
    }

    private fun resolveIconForAod(pkg: String, notification: Notification): Icon? {
        if (notification.extras?.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON) == true) {
            return notification.smallIcon
        }
        val channelId = try { notification.channelId } catch (_: Throwable) { null }
        if (!channelId.isNullOrEmpty()) {
            val ctx = try {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null) as? android.content.Context
            } catch (_: Throwable) { null }
            if (ctx != null) {
                val override = IconUtils.channelOverrideIcon(pkg, channelId, ctx, "aod")
                if (override != null) {
                    IconUtils.clearPackageBitmapCaches(pkg)
                    IconUtils.iconCache[pkg] = override
                    return override
                }
            }
        }
        return IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification)
    }

    fun hookZygoteSbnMethods() {
        val sbnClass = StatusBarNotification::class.java

        XposedBridge.hookAllMethods(sbnClass, "getNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isModuleEnabled()) return
                try {
                    val pkg = XposedHelpers.getObjectField(param.thisObject, "pkg") as? String ?: return
                    val notification = param.result as? Notification ?: return
                    tryEarlyPatch(pkg, notification)
                    if (notification.extras?.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON) == true) {
                        val patchedIcon = notification.smallIcon
                        // Only cache if it's a bitmap (type 1 = our SVG). If type 2 (resource),
                        // setObjectField likely failed — don't overwrite a potentially good cache.
                        if (patchedIcon != null && patchedIcon.type == 1) {
                            IconUtils.clearPackageBitmapCaches(pkg)
                            IconUtils.iconCache[pkg] = patchedIcon
                        }
                        return
                    }
                    // Skip re-resolution only when there is definitely no channel override
                    // configured for this notification.  If a channel override exists (even if
                    // it failed to load before), always re-resolve so a freshly copied icon
                    // file is picked up without needing to restart the AOD process.
                    if (pkg != "com.android.systemui" && IconUtils.iconCache.containsKey(pkg)) {
                        val channelId = try { notification.channelId } catch (_: Throwable) { null }
                        val hasOverride = !channelId.isNullOrEmpty() &&
                            ru.valanis.oneplus.hook.core.ChannelIconStore.getAodPath(pkg, channelId) != null
                        if (!hasOverride) return
                    }
                    val icon = resolveIconForAod(pkg, notification) ?: return
                    IconUtils.iconCache[pkg] = icon
                } catch (_: Throwable) {}
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getPackageName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isModuleEnabled()) return
                val pkg = param.result as? String ?: return
                try {
                    val notification = XposedHelpers.getObjectField(param.thisObject, "notification") as? Notification ?: return
                    tryEarlyPatch(pkg, notification)
                    if (notification.extras?.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON) == true) {
                        val patchedIcon = notification.smallIcon
                        if (patchedIcon != null && patchedIcon.type == 1) {
                            IconUtils.clearPackageBitmapCaches(pkg)
                            IconUtils.iconCache[pkg] = patchedIcon
                        }
                        return
                    }
                    if (pkg != "com.android.systemui" && IconUtils.iconCache.containsKey(pkg)) {
                        val channelId = try { notification.channelId } catch (_: Throwable) { null }
                        val hasOverride = !channelId.isNullOrEmpty() &&
                            ru.valanis.oneplus.hook.core.ChannelIconStore.getAodPath(pkg, channelId) != null
                        if (!hasOverride) return
                    }
                    val icon = resolveIconForAod(pkg, notification) ?: return
                    IconUtils.iconCache[pkg] = icon
                } catch (_: Throwable) {}
            }
        })
    }

    fun hookZygotePmGetApplicationIcon() {
        val pmClass = Class.forName("android.app.ApplicationPackageManager")
        XposedBridge.hookAllMethods(pmClass, "getApplicationIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isAodProcess || !PreferenceUtils.isAodEnabled()) return
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

    fun hookZygoteNotificationGetLargeIcon() {
        XposedBridge.hookAllMethods(Notification::class.java, "getLargeIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isAodProcess || !PreferenceUtils.isAodEnabled()) return
                try {
                    val notification = param.thisObject as Notification
                    val smallIcon = originalSmallIcon(notification) ?: return
                    param.result = smallIcon
                } catch (_: Throwable) {}
            }
        })
    }

    fun hookZygoteDexClassLoaderInit() {
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

    fun hookEngineLoaderInProcess() {
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
    //  com.oplus.aod process hooks
    // ═══════════════════════════════════════════════

    fun hookAodProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.e(TAG, "hookAodProcess: pid=${android.os.Process.myPid()} pkg=${lpparam.packageName}")
        try {
            val cfgClass = XposedHelpers.findClass(
                "com.oplus.content.OplusFeatureConfigManager", lpparam.classLoader
            )
            XposedBridge.hookAllMethods(cfgClass, "hasFeature", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    val feature = param.args.getOrNull(0) as? String ?: return
                    if (feature == "oplus.software.display.aod_ramless_support") {
                        param.result = false
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "hookAodProcess: OplusFeatureConfigManager hook failed: ${t.message}")
        }
        hookUIEngineManagerInit(lpparam)
    }

    // ═══════════════════════════════════════════════
    //  Private implementation
    // ═══════════════════════════════════════════════

    private fun hookAodNotificationLayoutSmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val layoutClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.aodclock.off.notification.NotificationLayout",
                lpparam.classLoader
            )
            // onReceiveNotification(SBN[] allActive, SBN incoming, boolean flag)
            XposedBridge.hookAllMethods(layoutClass, "onReceiveNotification", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    try {
                        // Patch the incoming SBN (arg1) before the layout renders it
                        val sbn = param.args.getOrNull(1) ?: return
                        val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                        tryEarlyPatch(pkg, notification)
                    } catch (_: Throwable) {}
                }
            })
            Log.e(TAG, "hookAodNotificationLayoutSmallIcon: hooked NotificationLayout.onReceiveNotification")
        } catch (t: Throwable) {
            Log.e(TAG, "hookAodNotificationLayoutSmallIcon FAILED: ${t.message}")
        }
    }

    private fun hookAodCurvedDisplaySmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CurvedDisplayManager posts ShowCurvedNotification (a Runnable) to a Handler.
        // When run() fires, mNotification holds the SBN to display on the curved edge.
        try {
            val runnableClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.common.CurvedDisplayManager\$ShowCurvedNotification",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(runnableClass, "run", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    try {
                        val sbn = XposedHelpers.getObjectField(param.thisObject, "mNotification")
                            as? StatusBarNotification ?: return
                        val pkg = sbn.packageName
                        val notification = sbn.notification
                        tryEarlyPatch(pkg, notification)
                        // Also patch mIncomingNotiPaint.mDrawable on the curved view
                        val mgr = XposedHelpers.getObjectField(param.thisObject, "instance") ?: return
                        val view = XposedHelpers.getObjectField(mgr, "mCurvedDisplayView") ?: return
                        val paint = XposedHelpers.getObjectField(view, "mIncomingNotiPaint") ?: return
                        val channelId = try { notification.channelId } catch (_: Throwable) { null }
                        if (channelId.isNullOrEmpty()) return
                        val ctx = try {
                            Class.forName("android.app.ActivityThread")
                                .getMethod("currentApplication").invoke(null) as? android.content.Context
                        } catch (_: Throwable) { null } ?: return
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
            Log.e(TAG, "hookAodCurvedDisplaySmallIcon: hooked ShowCurvedNotification.run")
        } catch (t: Throwable) {
            Log.e(TAG, "hookAodCurvedDisplaySmallIcon FAILED: ${t.message}")
        }
    }

    /**
     * Patches SBNs returned by AodNotificationListenerService.getActiveNotifications() so that
     * notifications already showing on AOD (before this hook was installed) get the correct icon
     * the next time the layout refreshes its view.
     */
    private fun hookAodNotificationListenerGetActiveNotifications(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val serviceClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.common.AodNotificationListenerService",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(serviceClass, "getActiveNotifications", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!PreferenceUtils.isAodEnabled()) return
                    val sbnArray = param.result as? Array<*> ?: return
                    for (sbn in sbnArray) {
                        sbn ?: continue
                        try {
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: continue
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: continue
                            tryEarlyPatch(pkg, notification)
                        } catch (_: Throwable) {}
                    }
                }
            })
            Log.e(TAG, "hookAodNotificationListenerGetActiveNotifications: hooked getActiveNotifications")
        } catch (t: Throwable) {
            Log.e(TAG, "hookAodNotificationListenerGetActiveNotifications FAILED: ${t.message}")
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
                                Log.e(TAG, "UIEngineManager.init: engineClassLoader field not found")
                                return
                            }
                            if (!engineHooked.compareAndSet(false, true)) {
                                Log.e(TAG, "UIEngineManager.init: engine already hooked, skip")
                                return
                            }
                            Log.e(TAG, "UIEngineManager.init: hooking engine classes via $engineCL")
                            hookAllEngineClasses(engineCL)
                        } catch (t: Throwable) {
                            Log.e(TAG, "UIEngineManager.init error: ${t.message}")
                        }
                    }
                }
            )
            Log.e(TAG, "hookUIEngineManagerInit: hooked UIEngineManager.init")
        } catch (t: Throwable) {
            Log.e(TAG, "hookUIEngineManagerInit FAILED: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════
    //  Engine hooks (AOD VariUIEngine)
    // ═══════════════════════════════════════════════

    private fun hookAllEngineClasses(engineCL: ClassLoader) {
        var ok = 0
        try { hookEngineUpdateNotificationIconData(engineCL); ok++ } catch (t: Throwable) {
            Log.e(TAG, "engine hookUpdateNotificationIconData FAILED: ${t.message}")
        }
        try { hookEngineNotificationView(engineCL); ok++ } catch (t: Throwable) {
            Log.e(TAG, "engine hookNotificationView FAILED: ${t.message}")
        }
        try { hookEngineEgCommonHelper(engineCL); ok++ } catch (t: Throwable) {
            Log.e(TAG, "engine hookEgCommonHelper FAILED: ${t.message}")
        }
        try { hookEngineGlideLoader(engineCL); ok++ } catch (t: Throwable) {
            Log.e(TAG, "engine hookGlideLoader FAILED: ${t.message}")
        }
        Log.e(TAG, "hookAllEngineClasses: $ok/4 hooks installed")
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

                    Log.e(TAG, "updateNotificationIconData: pkgs=${pkgArray.toList()}")

                    for (i in pkgArray.indices) {
                        val pkg = pkgArray[i] as? String ?: continue
                        if (pkg.isEmpty()) continue

                        val originalDrawable = drawableArray[i] as? Drawable
                        val targetW = originalDrawable?.intrinsicWidth ?: 0
                        val targetH = originalDrawable?.intrinsicHeight ?: 0

                        val cachedIcon = IconUtils.iconCache[pkg]
                        Log.e(TAG, "updateNotificationIconData[$pkg]: cachedIcon=${cachedIcon?.type}")
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
                                    val baseKey = IconUtils.getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
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

                    val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                    val smallIcon = resolveIconForAod(pkg, notification) ?: return
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
