package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.PreferenceUtils
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("PrivateApi")
object ShadeHook {

    private const val MAX_CACHE = 60
    private val appIconCache = ConcurrentHashMap<String, Drawable>()

    fun getCachedAppIcon(pkg: String, context: android.content.Context): Drawable {
        appIconCache[pkg]?.let { cached ->
            return cached.constantState?.newDrawable()?.mutate() ?: cached
        }
        val loaded = context.packageManager.getApplicationIcon(pkg)
        if (appIconCache.size >= MAX_CACHE) {
            appIconCache.keys.take(MAX_CACHE / 4).forEach { appIconCache.remove(it) }
        }
        appIconCache[pkg] = loaded
        return loaded
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val expandableRowClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            lpparam.classLoader
        )

        val getEntryMethod: Method = expandableRowClass.getDeclaredMethod("getEntry")
        val getSbnMethodCache = ConcurrentHashMap<Class<*>, Method>()

        fun setShadeIcon(wrapper: Any, mRow: Any?) {
            if (!PreferenceUtils.isShadeEnabled()) return
            try {
                val row = mRow ?: XposedHelpers.getObjectField(wrapper, "mRow") ?: return
                val mIcon = XposedHelpers.getObjectField(wrapper, "mIcon") as? ImageView ?: return
                val entry = getEntryMethod.invoke(row) ?: return
                val getSbn = getSbnMethodCache.getOrPut(entry.javaClass) {
                    entry.javaClass.getDeclaredMethod("getSbn")
                }
                val sbn = getSbn.invoke(entry) ?: return
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return

                mIcon.setImageDrawable(getCachedAppIcon(pkg, mIcon.context))
            } catch (_: Throwable) {}
        }

        val pairs = listOf(
            "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp" to "proxyOnContentUpdated",
            "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper" to "onContentUpdated",
            "com.android.systemui.statusbar.notification.NotificationHeaderViewWrapper" to "onContentUpdated"
        )
        for ((name, methodName) in pairs) {
            try {
                val wrapperClass = XposedHelpers.findClass(name, lpparam.classLoader)
                val rowArgIdx = if (methodName == "proxyOnContentUpdated") 0 else -1
                XposedBridge.hookAllMethods(wrapperClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mRow = if (rowArgIdx >= 0) param.args.getOrNull(rowArgIdx) else null
                        setShadeIcon(param.thisObject, mRow)
                    }
                })
            } catch (_: Throwable) {}
        }
    }
}
