package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.drawable.Drawable
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.IconUtils
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
                val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
                val channelId = try { notification?.channelId } catch (_: Throwable) { null } ?: ""

                if (channelId.isNotEmpty() && ChannelIconStore.getShadePath(pkg, channelId) != null) {
                    // Always set channel icon explicitly — guarantees correct icon even on first draw
                    val channelIcon = IconUtils.channelOverrideIcon(pkg, channelId, mIcon.context, "shade")
                    val d = channelIcon?.loadDrawable(mIcon.context)
                    if (d != null) {
                        mIcon.setImageDrawable(d)
                        return
                    }
                }
                mIcon.setImageDrawable(getCachedAppIcon(pkg, mIcon.context))
            } catch (_: Throwable) {}
        }

        val pairs = listOf(
            "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp" to "proxyOnContentUpdated",
            "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper" to "onContentUpdated",
            "com.android.systemui.statusbar.notification.NotificationHeaderViewWrapper" to "onContentUpdated",
            "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupTemplateWrapper" to "onContentUpdated"
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

        // Hook OPlus group notification row icon views (the small icons shown in a collapsed group)
        try {
            val rowIconViewClass = XposedHelpers.findClass(
                "com.oplus.systemui.notification.row.oplusgroup.OplusGroupNotificationRowIconView",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(rowIconViewClass, "setIconDrawable", object : XC_MethodHook() {
                private val inHook = ThreadLocal<Boolean>()

                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (inHook.get() == true) return
                    if (!PreferenceUtils.isShadeEnabled()) return
                    try {
                        val view = param.thisObject as? ImageView ?: return

                        // Resolve notification entry: try direct field, then view hierarchy
                        var entry: Any? = null
                        for (fname in listOf("mEntry", "entry", "mNotificationEntry")) {
                            try {
                                entry = XposedHelpers.getObjectField(view, fname)
                                if (entry != null) break
                            } catch (_: Throwable) {}
                        }
                        if (entry == null) {
                            var p: android.view.ViewParent? = view.parent ?: return
                            var depth = 0
                            while (p != null && depth < 12) {
                                if (expandableRowClass.isInstance(p)) {
                                    entry = getEntryMethod.invoke(p)
                                    break
                                }
                                p = (p as? android.view.View)?.parent
                                depth++
                            }
                        }
                        val e = entry ?: return
                        val getSbn = getSbnMethodCache.getOrPut(e.javaClass) {
                            e.javaClass.getDeclaredMethod("getSbn")
                        }
                        val sbn = getSbn.invoke(e) ?: return
                        val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
                        val channelId = try { notification?.channelId } catch (_: Throwable) { null } ?: ""
                        val ctx = view.context

                        if (ChannelIconStore.isMonochrome(pkg, channelId)) return

                        val replacement: android.graphics.drawable.Drawable? =
                            if (channelId.isNotEmpty() && ChannelIconStore.getShadePath(pkg, channelId) != null) {
                                IconUtils.channelOverrideIcon(pkg, channelId, ctx, "shade")?.loadDrawable(ctx)
                                    ?: getCachedAppIcon(pkg, ctx)
                            } else {
                                getCachedAppIcon(pkg, ctx)
                            }

                        // Replace first Drawable argument with correct icon
                        for (i in param.args.indices) {
                            if (param.args[i] is android.graphics.drawable.Drawable) {
                                inHook.set(true)
                                try { param.args[i] = replacement } finally { inHook.remove() }
                                return
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }
}
