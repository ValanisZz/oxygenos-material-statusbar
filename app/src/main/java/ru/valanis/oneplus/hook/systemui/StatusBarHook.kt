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

                        // Replace notification.smallIcon once so all system renderers use our icon
                        patchNotificationSmallIcon(pkgName, notification, context)

                        if (!allowConversationGroup) {
                            handleStatusBarIcon(result, iconField, pkgName, notification, context)
                        } else {
                            handleShadeIcon(result, iconField, pkgName, notification, context)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG SystemUI: ${t.message}")
                    }
                }
            }
        )
    }

    /**
     * Patches [Notification.mSmallIcon] with the channel override so that every system
     * renderer (shade, heads-up, AOD, lock-screen) automatically picks it up.
     * The original icon is saved to extras once so it can be restored later.
     */
    private fun patchNotificationSmallIcon(pkgName: String, notification: Notification, context: Context) {
        val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
        if (channelId.isEmpty()) return
        // Already patched?
        val extras = notification.extras ?: return
        if (extras.containsKey(IconUtils.EXTRAS_ORIGINAL_ICON)) return
        val override = IconUtils.channelOverrideIcon(pkgName, channelId, context) ?: return
        // Save original before replacing
        try { extras.putParcelable(IconUtils.EXTRAS_ORIGINAL_ICON, notification.smallIcon) } catch (_: Throwable) {}
        try {
            XposedHelpers.setObjectField(notification, "mSmallIcon", override)
        } catch (_: Throwable) {}
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
        val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
        val channelOverride = if (channelId.isNotEmpty())
            IconUtils.channelOverrideIcon(pkgName, channelId, context) else null
        val smallIcon = channelOverride
            ?: IconUtils.customSmallIcon(pkgName, notification, context)
            ?: originalSmallIcon(notification) ?: return
        val iconKey = IconUtils.getIconCacheKey(smallIcon, pkgName) ?: "bmp_${pkgName}_$channelId"
        val cacheKey = "sb_$iconKey"

        IconUtils.descriptorCache[cacheKey]?.let { cached ->
            iconField.set(result, cached)
            return
        }

        // Channel override icons are user-provided bitmaps — use as-is, no processing
        if (channelOverride != null) {
            IconUtils.evictIfNeeded(IconUtils.descriptorCache, IconUtils.MAX_DESCRIPTOR_CACHE)
            IconUtils.descriptorCache[cacheKey] = channelOverride
            iconField.set(result, channelOverride)
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
        notification: Notification,
        context: Context
    ) {
        val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
        val shadeCacheKey = if (channelId.isNotEmpty()) "shade_${pkgName}_$channelId" else "shade_$pkgName"
        IconUtils.descriptorCache[shadeCacheKey]?.let { cached ->
            iconField.set(result, cached)
            return
        }
        val override = IconUtils.channelOverrideIcon(pkgName, channelId, context)
        val finalIcon = if (override != null) {
            override
        } else {
            val appIcon = ShadeHook.getCachedAppIcon(pkgName, context)
            Icon.createWithBitmap(appIcon.toBitmap())
        }
        IconUtils.evictIfNeeded(IconUtils.descriptorCache, IconUtils.MAX_DESCRIPTOR_CACHE)
        IconUtils.descriptorCache[shadeCacheKey] = finalIcon
        iconField.set(result, finalIcon)
    }
}
