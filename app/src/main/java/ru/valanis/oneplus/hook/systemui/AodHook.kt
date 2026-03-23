package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
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

    fun hookZygoteSbnMethods() {
        val sbnClass = StatusBarNotification::class.java

        XposedBridge.hookAllMethods(sbnClass, "getNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isModuleEnabled()) return
                try {
                    val pkg = XposedHelpers.getObjectField(param.thisObject, "pkg") as? String ?: return
                    if (pkg != "com.android.systemui" && IconUtils.iconCache.containsKey(pkg)) return
                    val notification = param.result as? Notification ?: return
                    val icon = IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification) ?: return
                    IconUtils.iconCache[pkg] = icon
                } catch (_: Throwable) {}
            }
        })

        XposedBridge.hookAllMethods(sbnClass, "getPackageName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isModuleEnabled()) return
                val pkg = param.result as? String ?: return
                if (pkg != "com.android.systemui" && IconUtils.iconCache.containsKey(pkg)) return
                try {
                    val notification = XposedHelpers.getObjectField(param.thisObject, "notification") as? Notification ?: return
                    val icon = IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification) ?: return
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
        } catch (_: Throwable) {}
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
            val sbnClass = XposedHelpers.findClass(
                "android.service.notification.StatusBarNotification",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                layoutClass, "updateNotificationView", sbnClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!PreferenceUtils.isAodEnabled()) return
                        try {
                            val sbn = param.args.getOrNull(0) ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                            val smallIcon = IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification) ?: return
                            val layout = param.thisObject as ViewGroup
                            val count = layout.childCount
                            if (count == 0) return
                            val lastChild = layout.getChildAt(count - 1) as? ImageView ?: return
                            val context = layout.context
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val iconDrawable = smallIcon.loadDrawable(pkgContext) ?: return
                            val mono = IconUtils.getCachedMonochrome(smallIcon, pkg, iconDrawable)
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
                        if (!PreferenceUtils.isAodEnabled()) return
                        try {
                            val sbn = param.args.getOrNull(0) as? StatusBarNotification ?: return
                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                            val smallIcon = IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification) ?: return
                            val view = param.thisObject
                            val paint = XposedHelpers.getObjectField(view, "mIncomingNotiPaint") ?: return
                            val context = XposedHelpers.getObjectField(paint, "mContext") as? Context ?: return
                            val pkgContext = try { context.createPackageContext(pkg, 0) } catch (_: Throwable) { context }
                            val iconDrawable = smallIcon.loadDrawable(pkgContext) ?: return
                            val monoDrawable = IconUtils.getCachedMonochrome(smallIcon, pkg, iconDrawable)
                            val iconRect = XposedHelpers.getObjectField(paint, "mIconRect") as? Rect ?: return
                            monoDrawable.bounds = iconRect
                            XposedHelpers.setObjectField(paint, "mDrawable", monoDrawable)
                        } catch (_: Throwable) {}
                    }
                }
            )
        } catch (_: Throwable) {}
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
                    val smallIcon = IconUtils.customSmallIcon(pkg, notification) ?: originalSmallIcon(notification) ?: return
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
