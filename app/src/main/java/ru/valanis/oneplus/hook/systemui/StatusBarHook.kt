package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.IconUtils.toBitmap
import ru.valanis.oneplus.hook.core.PreferenceUtils

@SuppressLint("PrivateApi")
object StatusBarHook {

    private const val TAG = "OxygenOSMaterialIcons"

    @Volatile
    private var appContext: Context? = null

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
        // Resolved once: this hook runs for every icon update of every notification
        val getSbn = notificationEntryClass.getDeclaredMethod("getSbn").apply { isAccessible = true }
        val iconField = XposedHelpers.findField(statusBarIconClass, "icon")
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
                    if (allowConversationGroup && !PreferenceUtils.isShadeEnabled() &&
                        !PreferenceUtils.isNotificationCategoryIconsEnabled()
                    ) return
                    try {
                        val context = appContext ?: run {
                            val iconBuilder = XposedHelpers.getObjectField(param.thisObject, "iconBuilder") ?: return
                            (XposedHelpers.getObjectField(iconBuilder, "context") as? Context ?: return)
                                .also { appContext = it }
                        }
                        // App updates change what a resource id points at: watch for them
                        ShadeHook.ensureIconThemeReceiver(context)
                        val sbn = getSbn.invoke(param.args[0]) as? StatusBarNotification ?: return
                        val pkgName = sbn.packageName ?: return
                        val notification = AodHook.noteNotification(sbn) ?: return

                        if (!allowConversationGroup) {
                            handleStatusBarIcon(result, iconField, pkgName, notification, context)
                            if (PreferenceUtils.isDebugLog()) {
                                @Suppress("DEPRECATION")
                                val saved = notification.extras?.getParcelable(IconUtils.EXTRAS_ORIGINAL_ICON) as? Icon
                                XposedBridge.log(
                                    "$TAG statusbar key=${sbn.key} group=${sbn.groupKey} " +
                                        "summary=${(notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                                        "post=${sbn.postTime} channel=${notification.channelId} " +
                                        "small=${notification.smallIcon} saved=$saved legacyRes=${notification.icon} " +
                                        "shown=${iconField.get(result)}"
                                )
                            }
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
        // The app's own icon as the framework hook saved it at post time
        @Suppress("DEPRECATION")
        val saved = try {
            notification.extras?.getParcelable(IconUtils.EXTRAS_ORIGINAL_ICON) as? Icon
        } catch (_: Throwable) { null }
        val smallIcon = channelOverride
            ?: IconUtils.customSmallIcon(pkgName, notification, context)
            ?: saved ?: notification.smallIcon ?: return
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
        val iconType = try { smallIcon.type } catch (_: Throwable) { -1 }

        if (iconType == Icon.TYPE_RESOURCE) {
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
            // The legacy resource id is only a way to recover the app's icon when the OS has
            // replaced the small icon and nothing was saved. With a saved icon it must not be
            // used: Notification.Builder never clears it, so it can name a previous icon
            // (missed call) while the current one (chat) is a bitmap.
            @Suppress("DEPRECATION")
            val resId = if (saved == null) notification.icon else 0
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
        // Shade option off: only per-category icons are applied
        if (!PreferenceUtils.isShadeEnabled() &&
            (channelId.isEmpty() || ChannelIconStore.getPath(pkgName, channelId) == null)
        ) return
        val shadeCacheKey =if (channelId.isNotEmpty()) "shade_${pkgName}_$channelId" else "shade_$pkgName"
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
