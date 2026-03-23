package ru.valanis.oneplus.hook.framework

import android.annotation.SuppressLint
import android.app.Notification
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.PreferenceUtils

@SuppressLint("PrivateApi")
object FrameworkHook {

    private const val TAG = "OxygenOSMaterialIcons"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                        if (!PreferenceUtils.isModuleEnabled()) return
                        val notif = param.args.getOrNull(0) as? Notification ?: return
                        try {
                            val orig = notif.smallIcon
                            if (orig != null) {
                                notif.extras.putParcelable(IconUtils.EXTRAS_ORIGINAL_ICON, orig)
                            }
                        } catch (_: Throwable) {}
                        if (PreferenceUtils.isStatusBarEnabled()) {
                            param.result = notif
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Framework hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Framework: ${t.message}")
        }
    }
}
