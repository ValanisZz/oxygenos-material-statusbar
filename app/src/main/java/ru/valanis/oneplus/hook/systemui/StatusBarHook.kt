package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.IconUtils.toBitmap
import ru.valanis.oneplus.hook.core.PreferenceUtils

@SuppressLint("PrivateApi")
object StatusBarHook {

    private const val TAG = "OxygenOSMaterialIcons"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                    if (!allowConversationGroup && !PreferenceUtils.isStatusBarEnabled()) return
                    if (allowConversationGroup && !PreferenceUtils.isShadeEnabled()) return
                    try {
                        val iconBuilder = XposedHelpers.getObjectField(param.thisObject, "iconBuilder") ?: return
                        val context = XposedHelpers.getObjectField(iconBuilder, "context") as? Context ?: return
                        val entry = param.args[0]
                        val sbn = notificationEntryClass.getDeclaredMethod("getSbn").invoke(entry) ?: return
                        val pkgName = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification") as Notification
                        val iconField = XposedHelpers.findField(statusBarIconClass, "icon")

                        if (!allowConversationGroup) {
                            handleStatusBarIcon(result, iconField, pkgName, notification, context)
                        } else {
                            handleShadeIcon(result, iconField, pkgName, context)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG SystemUI: ${t.message}")
                    }
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun originalSmallIcon(notification: Notification): Icon? {
        val saved = try {
            notification.extras?.getParcelable(IconUtils.EXTRAS_ORIGINAL_ICON) as? Icon
        } catch (_: Throwable) { null }
        return saved ?: notification.smallIcon
    }

    private fun handleStatusBarIcon(
        result: Any,
        iconField: java.lang.reflect.Field,
        pkgName: String,
        notification: Notification,
        context: Context
    ) {
        val smallIcon = IconUtils.customSmallIcon(pkgName, notification) ?: originalSmallIcon(notification) ?: return
        val iconKey = IconUtils.getIconCacheKey(smallIcon, pkgName) ?: "bmp_$pkgName"
        val cacheKey = "sb_$iconKey"

        IconUtils.descriptorCache[cacheKey]?.let { cached ->
            iconField.set(result, cached)
            return
        }

        var finalIcon: Icon = smallIcon
        val iconType = try { XposedHelpers.getIntField(smallIcon, "mType") } catch (_: Throwable) { -1 }

        if (iconType == 2) {
            val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
            val themedD = try { smallIcon.loadDrawable(pkgContext) } catch (_: Throwable) { null }

            if (themedD != null && IconUtils.isColoredIcon(themedD.toBitmap())) {
                val rawD = IconUtils.loadIconWithoutTheme(smallIcon, pkgName, context)
                if (rawD == null || IconUtils.isColoredIcon(rawD.toBitmap())) {
                    val d = rawD ?: themedD
                    val mono = IconUtils.getCachedMonochrome(smallIcon, pkgName, d)
                    finalIcon = Icon.createWithBitmap(mono.toBitmap())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val resId = notification.icon
            if (resId != 0) {
                try {
                    finalIcon = Icon.createWithResource(pkgName, resId)
                } catch (_: Throwable) {
                    val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
                    val d = smallIcon.loadDrawable(pkgContext)
                    if (d != null) {
                        val mono = IconUtils.getCachedMonochrome(smallIcon, pkgName, d)
                        finalIcon = Icon.createWithBitmap(mono.toBitmap())
                    }
                }
            } else {
                val pkgContext = try { context.createPackageContext(pkgName, 0) } catch (_: Throwable) { context }
                val d = smallIcon.loadDrawable(pkgContext)
                if (d != null) {
                    finalIcon = Icon.createWithBitmap(IconUtils.getCachedMonochrome(smallIcon, pkgName, d).toBitmap())
                }
            }
        }

        IconUtils.evictIfNeeded(IconUtils.descriptorCache, IconUtils.MAX_DESCRIPTOR_CACHE)
        IconUtils.descriptorCache[cacheKey] = finalIcon
        iconField.set(result, finalIcon)
    }

    private fun handleShadeIcon(
        result: Any,
        iconField: java.lang.reflect.Field,
        pkgName: String,
        context: Context
    ) {
        val shadeCacheKey = "shade_$pkgName"
        IconUtils.descriptorCache[shadeCacheKey]?.let { cached ->
            iconField.set(result, cached)
            return
        }
        val appIcon = ShadeHook.getCachedAppIcon(pkgName, context)
        val appBitmapIcon = Icon.createWithBitmap(appIcon.toBitmap())
        IconUtils.evictIfNeeded(IconUtils.descriptorCache, IconUtils.MAX_DESCRIPTOR_CACHE)
        IconUtils.descriptorCache[shadeCacheKey] = appBitmapIcon
        iconField.set(result, appBitmapIcon)
    }
}
