package ru.valanis.oneplus.hook.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.PreferenceUtils

/**
 * ColorOS / OxygenOS 17 (Android 17, SDK 37) only.
 *
 * On 16 the header icon view takes whatever Icon RemoteViews binds, so [ShadeHook] swaps that
 * Icon on its way in. On 17 the view (com.android.internal.widget.NotificationRowIconView)
 * first asks an icon provider supplied by SystemUI: when the provider answers "launcher icon"
 * the view draws that drawable itself and drops the bound Icon, and otherwise it loads the
 * Icon through a path the 16 hooks do not see. Either way the stock icon was drawn first and
 * [ShadeHook] corrected it afterwards.
 *
 * Here the provider itself answers with the module's icon, so the view shows it from the
 * first bind and applies its own app-icon styling (no padding, no background, no tint).
 * [ShadeHook] still runs after it and finds the same icon in place.
 */
object ShadeHookCos17 {

    private const val PROVIDER =
        "com.android.systemui.statusbar.notification.row.icon." +
            "NotificationRowIconViewInflaterFactory\$createIconProvider\$2"

    // NotificationRowIconView.ICON_TYPE_*
    private const val TYPE_SMALL_ICON = 0
    private const val TYPE_LAUNCHER_ICON = 1
    private const val TYPE_BRIDGED_ICON = 2

    private class Target(val pkg: String, val context: Context, val channelPath: String?, val tinted: Boolean)

    /** What the module shows for this provider's notification, or null to leave it stock. */
    private fun target(provider: Any): Target? {
        val shadeEnabled = PreferenceUtils.isShadeEnabled()
        val categoryEnabled = PreferenceUtils.isNotificationCategoryIconsEnabled()
        if (!shadeEnabled && !categoryEnabled) return null
        val sbn = XposedHelpers.getObjectField(provider, "\$sbn") as? StatusBarNotification ?: return null
        val context = XposedHelpers.getObjectField(provider, "\$context") as? Context ?: return null
        val pkg = sbn.packageName ?: return null
        val channelId = try { AodHook.noteNotification(sbn)?.channelId } catch (_: Throwable) { null } ?: ""
        val channelPath =
            if (channelId.isNotEmpty() && categoryEnabled) ChannelIconStore.getShadePath(pkg, channelId) else null
        // Shade option off: only per-category icons are applied
        if (!shadeEnabled && channelPath == null) return null
        // Shade option off + monochrome category icon: stock look, tinted by the shade.
        // That one stays a small icon and is put in place by ShadeHook.
        val tinted = !shadeEnabled && ChannelIconStore.isMonochrome(pkg, channelId)
        return Target(pkg, context, channelPath, tinted)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val providerClass = XposedHelpers.findClass(PROVIDER, lpparam.classLoader)

        val typeHooks = XposedBridge.hookAllMethods(providerClass, "getIconType", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (param.result == TYPE_BRIDGED_ICON) return
                    val t = target(param.thisObject) ?: return
                    param.result = if (t.tinted) TYPE_SMALL_ICON else TYPE_LAUNCHER_ICON
                } catch (_: Throwable) {}
            }
        })
        if (typeHooks.isEmpty()) throw NoSuchMethodError("getIconType")

        val iconHooks = XposedBridge.hookAllMethods(providerClass, "getLauncherIcon", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val t = target(param.thisObject) ?: return
                    if (t.tinted) return
                    val base: Drawable = t.channelPath?.let { IconUtils.loadDrawableFromXmlPath(it, t.context) }
                        ?: ShadeHook.getCachedAppIcon(t.pkg, t.context)
                    param.result = ShadeHook.noTint(base)
                } catch (_: Throwable) {}
            }
        })
        if (iconHooks.isEmpty()) throw NoSuchMethodError("getLauncherIcon")
    }
}
