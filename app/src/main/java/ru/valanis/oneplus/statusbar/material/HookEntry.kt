package ru.valanis.oneplus.statusbar.material

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.pow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * OxygenOS Material Icons — LSPosed module.
 *
 * Replaces OxygenOS notification icons with standard Material-style icons:
 *   • Status bar  — original monochrome small icons (auto-tinted white↔dark).
 *   • Notification shade — colored app icons.
 *   • AOD (Always-on Display) — monochrome small icons (white silhouettes).
 *
 * Architecture:
 *   Framework: blocks OplusNotificationFixHelper.fixSmallIcon → preserves original TYPE_RESOURCE Icon.
 *   SystemUI:  getIconDescriptor → status bar: original Icon / monochrome fallback.
 *                                → shade: colored app icon.
 *              hookShadeForColoredIcons → forces app icon on notification headers.
 *   AOD:       VariUIEngine.apk loaded as PluginSeedling inside com.android.systemui.
 *              Hook on updateNotificationIconData replaces colored icons with
 *              monochrome small icons from iconCache (loaded without OxygenOS theme).
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "OxygenOSMaterialIcons"
        private const val MAX_MONOCHROME_CACHE = 100
        private const val MAX_FIT_CACHE = 80
        private const val MAX_RAW_ICON_CACHE = 50
        private const val MAX_DESCRIPTOR_CACHE = 100
        private const val MODULE_PKG = "ru.valanis.oneplus.statusbar.material"
        private val CUSTOM_SMALL_ICONS = mapOf(
            "com.android.mms" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_mms,
            "com.oplus.battery" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_battery_full,
            "com.oplus.phonemanager" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_phonemanager,
            "com.oneplus.deskclock" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_deskclock,
			"com.android.providers.donwloads" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_download,
			"com.oplus.ota" to ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_ota,
        )
    }

    private fun customSmallIcon(pkg: String, notification: Notification? = null): android.graphics.drawable.Icon? {
        val resId = CUSTOM_SMALL_ICONS[pkg]
        if (resId != null) return android.graphics.drawable.Icon.createWithResource(MODULE_PKG, resId)
        if (pkg == "com.android.systemui" && notification != null) {
            val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
            val category = try { notification.category } catch (_: Throwable) { null }
            val isBattery = channelId.contains("battery", ignoreCase = true) ||
                channelId.contains("bat", ignoreCase = true) ||  // BAT_NEW, etc.
                channelId.contains("CRG", ignoreCase = true) ||
                channelId.contains("charge", ignoreCase = true) ||
                channelId.contains("power", ignoreCase = true) ||
                channelId.contains("low", ignoreCase = true) ||
                (category != null && category.contains("battery", ignoreCase = true))
            if (isBattery) {
                return android.graphics.drawable.Icon.createWithResource(MODULE_PKG, ru.valanis.oneplus.statusbar.material.R.drawable.ic_notify_battery_full)
            }
        }
        return null
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Icon>()
    private val monochromeCache = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    private val fitCache = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    private val rawIconCache = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    /** Cache for final icon results in getIconDescriptor (status bar + shade). */
    private val descriptorCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Icon>()
    private val engineHooked = java.util.concurrent.atomic.AtomicBoolean(false)

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
            java.io.File("/proc/self/cmdline").readText().trim('\u0000')
        } catch (_: Throwable) { "" }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  initZygote — hooks inherited by ALL child processes
    // ═══════════════════════════════════════════════════════════════════

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        try { hookDisableRamlessFeature() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/ramless: ${t.message}")
        }
        try { hookZygoteSbnMethods() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/SBN: ${t.message}")
        }
        try { hookZygotePmGetApplicationIcon() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/PM: ${t.message}")
        }
        try { hookZygoteNotificationGetLargeIcon() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/LargeIcon: ${t.message}")
        }
        try { hookZygoteDexClassLoaderInit() } catch (t: Throwable) {
            XposedBridge.log("$TAG initZygote/DCL: ${t.message}")
        }
        XposedBridge.log("$TAG: initZygote complete")
    }

    // ─── Disable ramless AOD feature ───

    private fun hookDisableRamlessFeature() {
        val cfgClass = Class.forName("com.oplus.content.OplusFeatureConfigManager")
        XposedBridge.hookAllMethods(cfgClass, "hasFeature", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val feature = param.args.getOrNull(0) as? String ?: return
                if (feature == "oplus.software.display.aod_ramless_support") {
                    param.result = false
                }
            }
        })
    }

    // ─── SBN: cache small icon per package ───

    private fun hookZygoteSbnMethods() {
        val sbnClass = android.service.notification.StatusBarNotification::class.java

        XposedBridge.hookAllMethods(sbnClass, "getNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val pkg = XposedHelpers.getObjectField(param.thisObject, "pkg") as? String ?: return
                    // SystemUI sends varied notifications (battery, eye comfort, alerts)
                    // with different icons — must always update. Other packages are safe to cache.
                    if (pkg != "com.android.systemui" && iconCache.containsKey(pkg)) return
                    val notification = param.result as? Notification ?: return
                    val icon = customSmallIcon(pkg, notification) ?: notification.smallIcon ?: return
                    iconCache[pkg] = icon
                } catch (_: Throwable) {}
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getPackageName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.result as? String ?: return
                if (pkg != "com.android.systemui" && iconCache.containsKey(pkg)) return
                try {
                    val notification = XposedHelpers.getObjectField(param.thisObject, "notification") as? Notification ?: return
                    val icon = customSmallIcon(pkg, notification) ?: notification.smallIcon ?: return
                    iconCache[pkg] = icon
                } catch (_: Throwable) {}
            }
        })
    }

    // ─── PM.getApplicationIcon → small icon from cache (AOD process only) ───

    private fun hookZygotePmGetApplicationIcon() {
        val pmClass = Class.forName("android.app.ApplicationPackageManager")
        XposedBridge.hookAllMethods(pmClass, "getApplicationIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isAodProcess) return
                try {
                    val pkg: String = when (val arg = param.args.getOrNull(0)) {
                        is String -> arg
                        is android.content.pm.ApplicationInfo -> arg.packageName
                        else -> return
                    }
                    val cachedIcon = iconCache[pkg]
                    if (cachedIcon != null) {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context ?: return
                        val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                        val drawable = cachedIcon.loadDrawable(pkgContext)
                        if (drawable != null) {
                            param.result = drawable
                            return
                        }
                    }
                    val result = param.result as? Drawable ?: return
                    param.result = toWhiteSilhouette(result)
                } catch (_: Throwable) {}
            }
        })
    }

    // ─── Notification.getLargeIcon() → small icon (AOD process only) ───

    private fun hookZygoteNotificationGetLargeIcon() {
        XposedBridge.hookAllMethods(Notification::class.java, "getLargeIcon", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isAodProcess) return
                try {
                    val notification = param.thisObject as Notification
                    val smallIcon = notification.smallIcon ?: return
                    param.result = smallIcon
                } catch (_: Throwable) {}
            }
        })
    }

    // ─── DexClassLoader: intercept AOD engine loading ───

    private fun hookZygoteDexClassLoaderInit() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dexPath = param.args.getOrNull(0) as? String ?: return
                if (!dexPath.contains("uiengine", ignoreCase = true)) return
                if (!engineHooked.compareAndSet(false, true)) return
                val cl = param.thisObject as ClassLoader
                hookAllEngineClasses(cl)
            }
        }
        XposedBridge.hookAllConstructors(dalvik.system.DexClassLoader::class.java, hook)
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader::class.java, hook)
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════
    //  handleLoadPackage — per-app hooks
    // ═══════════════════════════════════════════════

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> hookFramework(lpparam)
            "com.android.systemui" -> hookSystemUI(lpparam)
            "com.oplus.aod" -> {
                try {
                    val cfgClass = XposedHelpers.findClass(
                        "com.oplus.content.OplusFeatureConfigManager", lpparam.classLoader
                    )
                    XposedBridge.hookAllMethods(cfgClass, "hasFeature", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val feature = param.args.getOrNull(0) as? String ?: return
                            if (feature == "oplus.software.display.aod_ramless_support") {
                                param.result = false
                            }
                        }
                    })
                } catch (_: Throwable) {}
                hookUIEngineManagerInit(lpparam)
            }
        }
    }

    // ─── Framework: block fixSmallIcon ───

    private fun hookFramework(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val helperClass = XposedHelpers.findClass(
                "com.android.server.notification.OplusNotificationFixHelper",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                helperClass,
                "fixSmallIcon",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val notif = param.args.getOrNull(0) as? Notification ?: return
                        param.result = notif
                    }
                }
            )
            XposedBridge.log("$TAG: Framework hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Framework: ${t.message}")
        }
    }

    // ─── SystemUI: status bar + shade + AOD ───

    private fun hookSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val iconManagerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.icon.IconManager",
                lpparam.classLoader
            )
            val notificationEntryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                lpparam.classLoader
            )
            val statusBarIconClass = XposedHelpers.findClass(
                "com.android.internal.statusbar.StatusBarIcon",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                iconManagerClass,
                "getIconDescriptor",
                notificationEntryClass,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result ?: return
                        val allowConversationGroup = param.args.getOrNull(1) as? Boolean ?: false
                        try {
                            val iconBuilder = XposedHelpers.getObjectField(param.thisObject, "iconBuilder") ?: return
                            val context = XposedHelpers.getObjectField(iconBuilder, "context") as? android.content.Context ?: return
                            val entry = param.args[0]
                            val sbn = notificationEntryClass.getDeclaredMethod("getSbn").invoke(entry) ?: return
                            val pkgName = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as Notification
                            val iconField = XposedHelpers.findField(statusBarIconClass, "icon")

                            if (!allowConversationGroup) {
                                // Status bar: tintable monochrome icon
                                val smallIcon = customSmallIcon(pkgName, notification) ?: notification.smallIcon ?: return
                                val iconKey = getIconCacheKey(smallIcon, pkgName) ?: "bmp_$pkgName"
                                val cacheKey = "sb_$iconKey"

                                // Return cached result if available
                                descriptorCache[cacheKey]?.let { cached ->
                                    iconField.set(result, cached)
                                    return
                                }

                                var finalIcon: android.graphics.drawable.Icon = smallIcon
                                val iconType = try { XposedHelpers.getIntField(smallIcon, "mType") } catch (_: Throwable) { -1 }

                                if (iconType == 2) {
                                    // TYPE_RESOURCE — check if OxygenOS themed it to colored
                                    val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
                                    val themedD = try { smallIcon.loadDrawable(pkgContext) } catch (_: Throwable) { null }

                                    if (themedD != null && isColoredIcon(themedD.toBitmap())) {
                                        val rawD = loadIconWithoutTheme(smallIcon, pkgName, context)
                                        if (rawD == null || isColoredIcon(rawD.toBitmap())) {
                                            val d = rawD ?: themedD
                                            val mono = getCachedMonochrome(smallIcon, pkgName, d)
                                            finalIcon = android.graphics.drawable.Icon.createWithBitmap(mono.toBitmap())
                                        }
                                        // else: rawD is monochrome — keep original resource icon (finalIcon = smallIcon)
                                    }
                                    // else: themed drawable is already monochrome — keep original
                                } else {
                                    // TYPE_BITMAP → try TYPE_RESOURCE from resId
                                    @Suppress("DEPRECATION")
                                    val resId = notification.icon
                                    if (resId != 0) {
                                        try {
                                            finalIcon = android.graphics.drawable.Icon.createWithResource(pkgName, resId)
                                        } catch (_: Throwable) {
                                            val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
                                            val d = smallIcon.loadDrawable(pkgContext)
                                            if (d != null) {
                                                val mono = getCachedMonochrome(smallIcon, pkgName, d)
                                                finalIcon = android.graphics.drawable.Icon.createWithBitmap(mono.toBitmap())
                                            }
                                        }
                                    } else {
                                        val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
                                        val d = smallIcon.loadDrawable(pkgContext)
                                        if (d != null) {
                                            finalIcon = android.graphics.drawable.Icon.createWithBitmap(getCachedMonochrome(smallIcon, pkgName, d).toBitmap())
                                        }
                                    }
                                }

                                // Cache and set the result
                                evictIfNeeded(descriptorCache, MAX_DESCRIPTOR_CACHE)
                                descriptorCache[cacheKey] = finalIcon
                                iconField.set(result, finalIcon)
                            } else {
                                // Notification shade: colored app icon
                                val shadeCacheKey = "shade_$pkgName"
                                descriptorCache[shadeCacheKey]?.let { cached ->
                                    iconField.set(result, cached)
                                    return
                                }
                                val appIcon = context.packageManager.getApplicationIcon(pkgName)
                                val appBitmapIcon = android.graphics.drawable.Icon.createWithBitmap(appIcon.toBitmap())
                                evictIfNeeded(descriptorCache, MAX_DESCRIPTOR_CACHE)
                                descriptorCache[shadeCacheKey] = appBitmapIcon
                                iconField.set(result, appBitmapIcon)
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG SystemUI: ${t.message}")
                        }
                    }
                }
            )
            hookShadeForColoredIcons(lpparam)
            hookSystemUIForAodIcons(lpparam)
            try { hookEngineLoaderInProcess() } catch (_: Throwable) {}
            XposedBridge.log("$TAG: SystemUI hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG SystemUI: ${t.message}")
        }
    }

    /** Shade: force colored app icons on notification headers. */
    private fun hookShadeForColoredIcons(lpparam: XC_LoadPackage.LoadPackageParam) {
        val expandableRowClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            lpparam.classLoader
        )
        fun setShadeIcon(wrapper: Any, mRow: Any?) {
            try {
                val row = mRow ?: XposedHelpers.getObjectField(wrapper, "mRow") ?: return
                val mIcon = XposedHelpers.getObjectField(wrapper, "mIcon") as? android.widget.ImageView ?: return
                val entry = expandableRowClass.getDeclaredMethod("getEntry").invoke(row) ?: return
                val sbn = entry.javaClass.getDeclaredMethod("getSbn").invoke(entry) ?: return
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                val appIcon = mIcon.context.packageManager.getApplicationIcon(pkg)
                mIcon.setImageDrawable(appIcon)
            } catch (_: Throwable) { }
        }
        val pairs = listOf(
            "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp" to listOf("proxyOnContentUpdated"),
            "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper" to listOf("onContentUpdated", "resolveHeaderViews"),
            "com.android.systemui.statusbar.notification.NotificationHeaderViewWrapper" to listOf("onContentUpdated", "resolveHeaderViews")
        )
        for ((name, methods) in pairs) {
            for (methodName in methods) {
                try {
                    val wrapperClass = XposedHelpers.findClass(name, lpparam.classLoader)
                    val rowArgIdx = if (methodName == "proxyOnContentUpdated") 0 else -1
                    XposedBridge.hookAllMethods(wrapperClass, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val wrapper = param.thisObject
                            val mRow = if (rowArgIdx >= 0) param.args.getOrNull(rowArgIdx) else null
                            setShadeIcon(wrapper, mRow)
                            mRow?.let { r ->
                                (r as? android.view.View)?.postDelayed({ setShadeIcon(wrapper, r) }, 200)
                            } ?: run {
                                val r = XposedHelpers.getObjectField(wrapper, "mRow")
                                (r as? android.view.View)?.postDelayed({ setShadeIcon(wrapper, r) }, 200)
                            }
                        }
                    })
                } catch (_: Throwable) { }
            }
        }
    }

    /** SystemUI-side AOD hooks */
    private fun hookSystemUIForAodIcons(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookAodCurvedDisplaySmallIcon(lpparam)
        hookAodNotificationLayoutSmallIcon(lpparam)
    }

    private fun hookAodNotificationLayoutSmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val layoutClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.aodclock.off.notification.NotificationLayout",
                lpparam.classLoader
            )
            val sbnClass = XposedHelpers.findClass(
                "android.service.notification.StatusBarNotification",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                layoutClass, "updateNotificationView", sbnClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val sbn = param.args.getOrNull(0) ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                            val smallIcon = customSmallIcon(pkg, notification) ?: notification.smallIcon ?: return
                            val layout = param.thisObject as android.view.ViewGroup
                            val count = layout.childCount
                            if (count == 0) return
                            val lastChild = layout.getChildAt(count - 1) as? android.widget.ImageView ?: return
                            val context = layout.context
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val iconDrawable = smallIcon.loadDrawable(pkgContext) ?: return
                            val mono = getCachedMonochrome(smallIcon, pkg, iconDrawable)
                            lastChild.setImageDrawable(mono)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookAodCurvedDisplaySmallIcon(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewClass = XposedHelpers.findClass(
                "com.oplus.systemui.aod.surface.OplusAodCurvedDisplayView",
                lpparam.classLoader
            )
            val sbnClass = XposedHelpers.findClass(
                "android.service.notification.StatusBarNotification",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                viewClass, "updateReceiveNotification", sbnClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val sbn = param.args.getOrNull(0) as? android.service.notification.StatusBarNotification ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                            val smallIcon = customSmallIcon(pkg, notification) ?: notification.smallIcon ?: return
                            val view = param.thisObject
                            val paint = XposedHelpers.getObjectField(view, "mIncomingNotiPaint") ?: return
                            val context = XposedHelpers.getObjectField(paint, "mContext") as? android.content.Context ?: return
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val iconDrawable = smallIcon.loadDrawable(pkgContext) ?: return
                            val monoDrawable = getCachedMonochrome(smallIcon, pkg, iconDrawable)
                            val iconRect = XposedHelpers.getObjectField(paint, "mIconRect") as? android.graphics.Rect ?: return
                            monoDrawable.setBounds(iconRect)
                            XposedHelpers.setObjectField(paint, "mDrawable", monoDrawable)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ─── Backup: DexClassLoader hook inside SystemUI process ───

    private fun hookEngineLoaderInProcess() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dexPath = param.args.getOrNull(0) as? String ?: return
                if (!dexPath.contains("uiengine", ignoreCase = true)) return
                if (!engineHooked.compareAndSet(false, true)) return
                val cl = param.thisObject as ClassLoader
                hookAllEngineClasses(cl)
            }
        }
        XposedBridge.hookAllConstructors(dalvik.system.DexClassLoader::class.java, hook)
        try {
            XposedBridge.hookAllConstructors(dalvik.system.BaseDexClassLoader::class.java, hook)
        } catch (_: Throwable) {}
    }

    // ─── AOD: UIEngineManager.init() backup ───

    private fun hookUIEngineManagerInit(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val uiemClass = XposedHelpers.findClass(
                "com.oplus.aod.uiengine.UIEngineManager",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(uiemClass, "init", android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val engineCL = XposedHelpers.getObjectField(param.thisObject, "engineClassLoader") as? ClassLoader ?: return
                            if (!engineHooked.compareAndSet(false, true)) return
                            hookAllEngineClasses(engineCL)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════
    //  Engine hooks (AOD VariUIEngine)
    // ═══════════════════════════════════════════════

    private fun hookAllEngineClasses(engineCL: ClassLoader) {
        try { hookEngineUpdateNotificationIconData(engineCL) } catch (_: Throwable) {}
        try { hookEngineNotificationView(engineCL) } catch (_: Throwable) {}
        try { hookEngineEgCommonHelper(engineCL) } catch (_: Throwable) {}
        try { hookEngineGlideLoader(engineCL) } catch (_: Throwable) {}
    }

    /** Main AOD hook: replace pre-resolved colored icons with monochrome small icons. */
    private fun hookEngineUpdateNotificationIconData(engineCL: ClassLoader) {
        val nvClass = engineCL.loadClass("com.oplus.egview.widget.NotificationView")
        XposedBridge.hookAllMethods(nvClass, "updateNotificationIconData", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val pkgNames = param.args[0] ?: return
                    val drawables = param.args[1] ?: return
                    val pkgArray = pkgNames as Array<*>
                    val drawableArray = drawables as Array<*>
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context ?: return

                    for (i in pkgArray.indices) {
                        val pkg = pkgArray[i] as? String ?: continue
                        if (pkg.isEmpty()) continue

                        val originalDrawable = drawableArray[i] as? Drawable
                        val targetW = originalDrawable?.intrinsicWidth ?: 0
                        val targetH = originalDrawable?.intrinsicHeight ?: 0

                        val cachedIcon = iconCache[pkg]
                        if (cachedIcon != null) {
                            // Priority 1: load resource WITHOUT OxygenOS theme
                            try {
                                val rawDrawable = loadIconWithoutTheme(cachedIcon, pkg, context)
                                if (rawDrawable != null) {
                                    val rawMono = getCachedMonochrome(cachedIcon, pkg, rawDrawable, rawPath = true)
                                    val rawMonoBm = rawMono.toBitmap()
                                    if (!isBadSilhouette(rawMonoBm)) {
                                        val baseKey = getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
                                        val fitted = fitToSize(rawMono, targetW, targetH, context.resources, "${baseKey}_raw")
                                        java.lang.reflect.Array.set(drawables, i, fitted)
                                        continue
                                    }
                                    // Bad result — evict so themed path can cache its (possibly better) result
                                    val baseKey = getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
                                    monochromeCache.remove("${baseKey}_raw")
                                }
                            } catch (_: Throwable) {}

                            // Priority 2: load with theme + ensureMonochrome
                            try {
                                val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                                val smallDrawable = cachedIcon.loadDrawable(pkgContext)
                                if (smallDrawable != null) {
                                    val mono = getCachedMonochrome(cachedIcon, pkg, smallDrawable)
                                    val baseKey = getIconCacheKey(cachedIcon, pkg) ?: "fallback_$pkg"
                                    val fitted = fitToSize(mono, targetW, targetH, context.resources, baseKey)
                                    java.lang.reflect.Array.set(drawables, i, fitted)
                                    continue
                                }
                            } catch (_: Throwable) {}
                        }

                        // Fallback: monochrome from original colored icon
                        if (originalDrawable != null) {
                            val mono = getCachedMonochromeFallback(pkg, originalDrawable)
                            java.lang.reflect.Array.set(drawables, i, mono)
                        }
                    }
                } catch (_: Throwable) {}
            }
        })
    }

    /** Backup: NotificationView.getPackageDrawable → small icon */
    private fun hookEngineNotificationView(engineCL: ClassLoader) {
        val nvClass = engineCL.loadClass("com.oplus.egview.widget.NotificationView")
        XposedBridge.hookAllMethods(nvClass, "getPackageDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val showList = param.args[0] as? java.util.List<*> ?: return
                    val pkg = param.args.getOrNull(1) as? String ?: "?"
                    val sbn = param.args[2] ?: return
                    if (showList.size >= 6) return

                    val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                    val smallIcon = customSmallIcon(pkg, notification) ?: notification.smallIcon ?: return
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? android.content.Context ?: return
                    val drawable = smallIcon.loadDrawable(context) ?: return
                    val monoDrawable = getCachedMonochrome(smallIcon, pkg, drawable)

                    try {
                        val roundedBitmap = XposedHelpers.callMethod(
                            param.thisObject, "drawableToRoundedBitmap", monoDrawable
                        ) as? android.graphics.Bitmap
                        if (roundedBitmap != null) {
                            param.result = BitmapDrawable(context.resources, roundedBitmap)
                            return
                        }
                    } catch (_: Throwable) { }
                    param.result = monoDrawable
                } catch (_: Throwable) {}
            }
        })
    }

    /** EgCommonHelper.getApplicationIcon → small icon from cache */
    private fun hookEngineEgCommonHelper(engineCL: ClassLoader) {
        try {
            val helperClass = engineCL.loadClass("com.oplus.egview.util.EgCommonHelper")
            XposedBridge.hookAllMethods(helperClass, "getApplicationIcon", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val context = param.args.getOrNull(0) as? android.content.Context ?: return
                        val pkg = param.args.getOrNull(1) as? String ?: return
                        val icon = iconCache[pkg]
                        if (icon != null) {
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val drawable = icon.loadDrawable(pkgContext)
                            if (drawable != null) {
                                param.result = drawable
                                return
                            }
                        }
                        val result = param.result as? Drawable ?: return
                        param.result = toWhiteSilhouette(result)
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    /** GlideLoaderKt.loadGeneric → small icon from cache */
    private fun hookEngineGlideLoader(engineCL: ClassLoader) {
        try {
            val loaderClass = engineCL.loadClass("com.oplus.egview.glide.GlideLoaderKt")
            XposedBridge.hookAllMethods(loaderClass, "loadGeneric", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val generic = param.args.getOrNull(0) as? String ?: return
                    val imageView = param.args.getOrNull(1) as? android.widget.ImageView ?: return
                    if (!looksLikePackageName(generic)) return
                    val cachedIcon = iconCache[generic] ?: return
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

    // ═══════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════

    /** Cache key for TYPE_RESOURCE Icon: "r_resPkg_resId". Returns null for TYPE_BITMAP (no cheap key). */
    private fun getIconCacheKey(icon: android.graphics.drawable.Icon, pkg: String): String? {
        try {
            val type = XposedHelpers.getIntField(icon, "mType")
            if (type != 2) return null
            val resId = try {
                icon.javaClass.getDeclaredMethod("getResId").apply { isAccessible = true }.invoke(icon) as Int
            } catch (_: Throwable) { XposedHelpers.getIntField(icon, "mInt1") }
            if (resId == 0) return null
            val resPkg = try {
                icon.javaClass.getDeclaredMethod("getResPackage").apply { isAccessible = true }.invoke(icon) as? String
            } catch (_: Throwable) { try { XposedHelpers.getObjectField(icon, "mString1") as? String } catch (_: Throwable) { null } } ?: pkg
            return "r_${resPkg}_$resId"
        } catch (_: Throwable) { return null }
    }

    /**
     * Returns cached monochrome drawable or computes and caches.
     * For TYPE_RESOURCE: caches by (resPkg, resId). For TYPE_BITMAP: caches by pkg only.
     * rawPath=true: key gets "_raw" suffix (loadIconWithoutTheme result ≠ themed loadDrawable).
     * Returns a copy (constantState.newDrawable) so callers can mutate (setBounds) safely.
     */
    private fun getCachedMonochrome(icon: android.graphics.drawable.Icon?, pkg: String, drawable: Drawable, rawPath: Boolean = false): Drawable {
        val baseKey = icon?.let { getIconCacheKey(it, pkg) } ?: "fallback_$pkg"
        val key = if (rawPath) "${baseKey}_raw" else baseKey
        evictIfNeeded(monochromeCache, MAX_MONOCHROME_CACHE)
        val cached = monochromeCache.getOrPut(key) { ensureMonochrome(drawable) }
        return cached.constantState?.newDrawable()?.mutate() ?: cached
    }

    /** Fallback cache for drawables without Icon (e.g. AOD app icon). Key: pkg only. */
    private fun getCachedMonochromeFallback(pkg: String, drawable: Drawable): Drawable {
        return getCachedMonochrome(null, pkg, drawable)
    }

    /**
     * Load Icon resource WITHOUT OxygenOS theme.
     * OxygenOS themes resources (e.g. battery) into colored bitmaps.
     * Loading with null theme returns the original Android resource (usually monochrome VectorDrawable).
     * Cached by (resPkg, resId) to avoid repeated resource loading.
     */
    private fun loadIconWithoutTheme(icon: android.graphics.drawable.Icon, pkg: String, context: android.content.Context): Drawable? {
        try {
            val iconType = XposedHelpers.getIntField(icon, "mType")
            if (iconType != 2) return null

            val resId = try {
                val m = icon.javaClass.getDeclaredMethod("getResId")
                m.isAccessible = true
                m.invoke(icon) as Int
            } catch (_: Throwable) {
                XposedHelpers.getIntField(icon, "mInt1")
            }
            if (resId == 0) return null

            val resPkg = try {
                val m = icon.javaClass.getDeclaredMethod("getResPackage")
                m.isAccessible = true
                m.invoke(icon) as? String
            } catch (_: Throwable) {
                try { XposedHelpers.getObjectField(icon, "mString1") as? String } catch (_: Throwable) { null }
            } ?: pkg

            val key = "raw_${resPkg}_$resId"
            evictIfNeeded(rawIconCache, MAX_RAW_ICON_CACHE)
            rawIconCache[key]?.let { cached ->
                return cached.constantState?.newDrawable()?.mutate() ?: cached
            }
            val pkgCtx = context.createPackageContext(resPkg, android.content.Context.CONTEXT_IGNORE_SECURITY)
            val d = pkgCtx.resources.getDrawable(resId, null) ?: return null
            rawIconCache[key] = d
            return d.constantState?.newDrawable()?.mutate() ?: d
        } catch (_: Throwable) {
            return null
        }
    }

    private fun looksLikePackageName(s: String): Boolean {
        if (s.length < 3 || !s[0].isLetter()) return false
        if (!s.contains('.')) return false
        // Heuristic: reject URLs and file paths without expensive filesystem I/O
        if (s.startsWith("http", ignoreCase = true) || s.startsWith("/") || s.contains("://")) return false
        if (s.contains('/') || s.contains('\\')) return false
        if (s.endsWith(".apk", ignoreCase = true) || s.endsWith(".dex", ignoreCase = true)) return false
        return true
    }

    /** Check if icon bitmap has significant color saturation. */
    private fun isColoredIcon(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return false

        val step = maxOf(1, minOf(width, height) / 12)
        var coloredPixels = 0
        var totalChecked = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 50) {
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    val saturation = if (max > 0) (max - min).toFloat() / max else 0f
                    totalChecked++
                    if (saturation > 0.2f) coloredPixels++
                }
                x += step
            }
            y += step
        }

        return totalChecked > 0 && coloredPixels.toFloat() / totalChecked > 0.3f
    }

    /**
     * Convert drawable to monochrome (white silhouette on transparent background).
     * Handles AdaptiveIconDrawable, transparent icons, opaque icons.
     */
    private fun ensureMonochrome(drawable: Drawable): Drawable {
        if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
            try {
                val monoLayer = drawable.javaClass.getMethod("getMonochrome").invoke(drawable) as? Drawable
                if (monoLayer != null) return toWhiteAlphaMask(monoLayer.toBitmap())
            } catch (_: Throwable) {}

            val fg = drawable.foreground
            if (fg != null) {
                val fgBitmap = fg.toBitmap()
                if (hasTransparency(fgBitmap)) return toWhiteAlphaMask(fgBitmap)
                val silhouette = toForegroundSilhouette(fgBitmap)
                val silBitmap = (silhouette as? BitmapDrawable)?.bitmap
                if (silBitmap != null && !isBadSilhouette(silBitmap)) return silhouette
                return toLuminanceAlpha(fgBitmap)
            }
        }

        val bitmap = drawable.toBitmap()
        val transparent = hasTransparency(bitmap)

        if (transparent) {
            val mask = toWhiteAlphaMask(bitmap)
            val maskBm = mask.toBitmap()
            if (!isBadSilhouette(maskBm)) return mask

            // White alpha mask is bad (icon fills >80% of canvas).
            // Force opaque (transparent → black bg) then extract silhouette by contrast.
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                pixels[i] = pixels[i] or (0xFF shl 24)
            }
            val opaqueBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            opaqueBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            opaqueBitmap.density = bitmap.density

            val silhouette = toForegroundSilhouette(opaqueBitmap)
            val silBm = (silhouette as? BitmapDrawable)?.bitmap
            if (silBm != null && !isBadSilhouette(silBm)) return silhouette
            val lumAlpha = toLuminanceAlpha(opaqueBitmap)
            val lumBm = lumAlpha.toBitmap()
            if (!isBadSilhouette(lumBm)) return lumAlpha
            return mask
        }

        // Opaque icon → extract foreground silhouette
        val silhouette = toForegroundSilhouette(bitmap)
        val silBitmap = (silhouette as? BitmapDrawable)?.bitmap
        if (silBitmap != null && isBadSilhouette(silBitmap)) return toLuminanceAlpha(bitmap)
        return silhouette
    }

    /** Thorough transparency check — samples all 4 edges, not just fixed points. */
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return false

        val corners = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)
        for ((cx, cy) in corners) {
            val alpha = (bitmap.getPixel(cx, cy) ushr 24) and 0xFF
            if (alpha < 200) return true
        }

        val step = maxOf(1, minOf(w, h) / 16)
        var transparentCount = 0
        var totalChecked = 0

        for (x in 0 until w step step) {
            val px = x.coerceIn(0, w - 1)
            val a1 = (bitmap.getPixel(px, 0) ushr 24) and 0xFF
            totalChecked++; if (a1 < 200) transparentCount++
            val a2 = (bitmap.getPixel(px, h - 1) ushr 24) and 0xFF
            totalChecked++; if (a2 < 200) transparentCount++
        }
        for (y in 0 until h step step) {
            val py = y.coerceIn(0, h - 1)
            val a1 = (bitmap.getPixel(0, py) ushr 24) and 0xFF
            totalChecked++; if (a1 < 200) transparentCount++
            val a2 = (bitmap.getPixel(w - 1, py) ushr 24) and 0xFF
            totalChecked++; if (a2 < 200) transparentCount++
        }

        return totalChecked > 0 && transparentCount.toFloat() / totalChecked > 0.10f
    }

    /** White alpha mask: every pixel → white (0xFFFFFF) with original alpha. */
    private fun toWhiteAlphaMask(src: Bitmap): Drawable {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        result.density = src.density
        return BitmapDrawable(android.content.res.Resources.getSystem(), result)
    }

    /** Extract foreground silhouette from opaque icon using edge-based background detection. */
    private fun toForegroundSilhouette(src: Bitmap): Drawable {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val edgePixels = mutableListOf<Int>()
        val stepX = maxOf(1, w / 10)
        val stepY = maxOf(1, h / 10)
        for (x in 0 until w step stepX) {
            edgePixels.add(pixels[x])
            edgePixels.add(pixels[(h - 1) * w + x])
        }
        for (y in 0 until h step stepY) {
            edgePixels.add(pixels[y * w])
            edgePixels.add(pixels[y * w + w - 1])
        }

        val bgR = edgePixels.map { (it ushr 16) and 0xFF }.sorted().let { it[it.size / 2] }
        val bgG = edgePixels.map { (it ushr 8) and 0xFF }.sorted().let { it[it.size / 2] }
        val bgB = edgePixels.map { it and 0xFF }.sorted().let { it[it.size / 2] }

        var maxDist = 0.0
        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val dist = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            if (dist > maxDist) maxDist = dist
        }

        if (maxDist < 15.0) return toLuminanceAlpha(src)

        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val distance = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            val normalized = (distance / maxDist).coerceIn(0.0, 1.0)
            val alpha = (normalized.pow(0.7) * 255.0).toInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        result.density = src.density
        return BitmapDrawable(android.content.res.Resources.getSystem(), result)
    }

    /** Quality check: >80% opaque or >90% transparent = bad silhouette. */
    private fun isBadSilhouette(bitmap: Bitmap): Boolean {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 8)
        var opaqueCount = 0
        var transparentCount = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                total++
                if (alpha > 200) opaqueCount++
                if (alpha < 30) transparentCount++
                x += step
            }
            y += step
        }
        if (total == 0) return true
        return opaqueCount.toFloat() / total > 0.80f || transparentCount.toFloat() / total > 0.90f
    }

    /** Luminance → alpha fallback for opaque icons. */
    private fun toLuminanceAlpha(src: Bitmap): Drawable {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var lumSum = 0L
        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lumSum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        val avgLum = if (pixels.isNotEmpty()) lumSum / pixels.size else 128L
        val invert = avgLum > 128

        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            var lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            if (invert) lum = 255 - lum
            val alpha = (lum * 2).coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        result.density = src.density
        return BitmapDrawable(android.content.res.Resources.getSystem(), result)
    }

    /** Scale drawable to target dimensions. Cached when cacheKey provided. */
    private fun fitToSize(drawable: Drawable, targetW: Int, targetH: Int, res: android.content.res.Resources, cacheKey: String? = null): Drawable {
        if (targetW <= 0 || targetH <= 0) return drawable
        val key = cacheKey?.let { "fit_${it}_${targetW}_${targetH}" }
        if (key != null) {
            evictIfNeeded(fitCache, MAX_FIT_CACHE)
            fitCache[key]?.let { cached ->
                return cached.constantState?.newDrawable()?.mutate() ?: cached
            }
        }
        val bitmap = drawable.toBitmap()
        if (bitmap.width == targetW && bitmap.height == targetH) return drawable
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        scaled.density = bitmap.density
        val result = BitmapDrawable(res, scaled)
        key?.let { fitCache[it] = result }
        return result.constantState?.newDrawable()?.mutate() ?: result
    }

    /** Evict oldest entries from a ConcurrentHashMap when it exceeds maxSize. */
    private fun <K, V> evictIfNeeded(cache: java.util.concurrent.ConcurrentHashMap<K, V>, maxSize: Int) {
        if (cache.size >= maxSize) {
            cache.keys.toList().take(maxSize / 4).forEach { cache.remove(it) }
        }
    }

    /** Desaturation fallback for legacy calls. */
    private fun toWhiteSilhouette(drawable: Drawable): Drawable {
        val src = drawable.toBitmap()
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        result.density = src.density
        return BitmapDrawable(android.content.res.Resources.getSystem(), result)
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap!!
        val w = kotlin.math.max(1, intrinsicWidth)
        val h = kotlin.math.max(1, intrinsicHeight)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bitmap
    }
}
