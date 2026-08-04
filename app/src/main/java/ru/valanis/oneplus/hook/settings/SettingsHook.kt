package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import android.os.Process
import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

@SuppressLint("PrivateApi")
object SettingsHook {

    private const val TAG = "OxygenOSMaterialIcons"

    /**
     * Oplus/COUI часто переопределяют [androidx.preference.PreferenceFragmentCompat.onResume]
     * без вызова super — хук только на базовом классе тогда не срабатывает.
     * Конкретные классы для экрана канала (AOSP Settings и Oplus NotificationCenter).
     */
    private val CHANNEL_ICON_FRAGMENT_CLASSES = listOf(
        "com.android.settings.notification.app.ChannelNotificationSettings",
        "com.android.settings.notification.app.ConversationNotificationSettings",
        "com.oplus.notificationmanager.fragments.channel.ChannelNotificationSettingsFragment"
    )

    private const val OPLUS_CHANNEL_ACTIVITY =
        "com.oplus.notificationmanager.ChannelNotificationSettingsActivity"

    private fun runChannelIconHooks(frag: Any, injectEntry: Boolean, knownChannelActivity: Boolean = false) {
        try {
            if (injectEntry) {
                if (SettingsScreenBuilder.tryBuild(frag)) return
                SettingsEntryInjector.inject(frag)
            }
            ChannelNotificationIconInjector.maybeInject(frag, knownChannelActivity = knownChannelActivity)
            ChannelNotificationIconInjector.syncVisibility(frag)
            (try {
                XposedHelpers.callMethod(frag, "getView") as? View
            } catch (_: Throwable) {
                null
            })?.post {
                try {
                    ChannelNotificationIconInjector.maybeInject(frag, knownChannelActivity = knownChannelActivity)
                    ChannelNotificationIconInjector.syncVisibility(frag)
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Settings channel/entry: ${t.message}")
        }
    }

    /**
     * Экран канала на Oplus часто в процессе `com.oplus.notificationmanager:ui`.
     * Берём фрагменты из Activity — надёжнее, чем надеяться на хук базового класса.
     */
    private fun injectChannelIconFromOplusActivity(activity: Any) {
        try {
            val fm = try {
                XposedHelpers.callMethod(activity, "getSupportFragmentManager")
            } catch (_: Throwable) {
                try { XposedHelpers.callMethod(activity, "getFragmentManager") } catch (_: Throwable) { null }
            }
            if (fm == null) {
                XposedBridge.log("$TAG injectFromOplusActivity: no FragmentManager")
                return
            }
            val fragments = try {
                XposedHelpers.callMethod(fm, "getFragments") as? List<*>
            } catch (_: Throwable) {
                null
            }
            if (fragments.isNullOrEmpty()) {
                XposedBridge.log("$TAG injectFromOplusActivity: fragments empty/null, trying direct PreferenceScreen on activity")
                tryInjectDirectlyIntoActivity(activity)
                return
            }
            val names = fragments.filterNotNull().map { it.javaClass.name }
            XposedBridge.log("$TAG injectFromOplusActivity: found ${names.size} fragments: $names")
            for (frag in fragments) {
                if (frag == null) continue
                val n = frag.javaClass.name
                if (n.contains("ChannelNotification", ignoreCase = true) ||
                    n.contains("NotificationChannel", ignoreCase = true) ||
                    n.contains("Preference", ignoreCase = true) ||
                    (n.contains("channel", ignoreCase = true) &&
                        n.contains("Notification", ignoreCase = true))
                ) {
                    Log.i(TAG, "inject from activity fragment=$n")
                    runChannelIconHooks(frag, false, knownChannelActivity = true)
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG injectFromOplusActivity: ${t.message}")
        }
    }

    /**
     * Oplus может рендерить экран канала без фрагментов (Activity напрямую).
     * Пробуем взять PreferenceScreen из activity и инжектить.
     */
    private fun tryInjectDirectlyIntoActivity(activity: Any) {
        try {
            ChannelNotificationIconInjector.maybeInject(activity)
            ChannelNotificationIconInjector.syncVisibility(activity)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG tryInjectDirectlyIntoActivity: ${t.message}")
        }
    }

    /**
     * `hookAllMethods(clazz, "onResume")` ищет метод ТОЛЬКО в [clazz], не в родителях.
     * Если Activity / Fragment не переопределяет `onResume` — хук молча не цепляется (0 методов).
     *
     * Надёжный подход: `findAndHookMethod` на **базовом** `Activity` / `Fragment` —
     * метод всегда определён в базе, колбэк фильтрует по конкретному имени класса.
     */
    private fun hookOplusChannelNotificationActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.thisObject.javaClass.name
                        if (!name.contains("ChannelNotificationSettings")) return
                        Log.i(TAG, "Activity.onResume $name pid=${Process.myPid()}")
                        injectChannelIconFromOplusActivity(param.thisObject)
                        try {
                            val decor = XposedHelpers.callMethod(param.thisObject, "getWindow")
                                .let { XposedHelpers.callMethod(it, "getDecorView") as? View }
                            decor?.post {
                                injectChannelIconFromOplusActivity(param.thisObject)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            )
            XposedBridge.log("$TAG: hooked Activity.onResume (filter ChannelNotificationSettings)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook Activity.onResume failed: ${t.message}")
        }
    }

    /**
     * Хук `Fragment.onResume` на базовом классе (метод там всегда есть).
     * Ловим и AndroidX, и platform Fragment.
     */
    private fun hookOplusNotificationManagerFragments(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fragmentClasses = listOf(
            "androidx.fragment.app.Fragment",
            "android.app.Fragment"
        )
        for (fc in fragmentClasses) {
            try {
                XposedHelpers.findAndHookMethod(
                    fc, lpparam.classLoader,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val name = param.thisObject.javaClass.name
                            if (!name.startsWith("com.oplus.notificationmanager") &&
                                !name.startsWith("com.oplus.notification")
                            ) return
                            Log.i(TAG, "NM Fragment.onResume $name pid=${Process.myPid()}")
                            if (name.contains("ChannelNotificationSettingsFragment") ||
                                name.contains("Preference", ignoreCase = true) ||
                                (name.contains("channel", ignoreCase = true) &&
                                    name.contains("Notification", ignoreCase = true) &&
                                    !name.contains("AppNotification", ignoreCase = true))
                            ) {
                                runChannelIconHooks(param.thisObject, false)
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG: hooked $fc.onResume for NM fragments")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: NM $fc hook failed: ${t.message}")
            }
        }
    }

    private fun hookConcreteChannelNotificationFragments(
        lpparam: XC_LoadPackage.LoadPackageParam,
        injectEntry: Boolean
    ) {
        for (className in CHANNEL_ICON_FRAGMENT_CLASSES) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                for (method in listOf("onResume", "onCreatePreferences")) {
                    val hooked = XposedBridge.hookAllMethods(clazz, method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            Log.i(TAG, "hooked $method ${param.thisObject.javaClass.name}")
                            runChannelIconHooks(param.thisObject, injectEntry)
                        }
                    })
                    if (hooked.isNotEmpty()) {
                        XposedBridge.log("$TAG: hooked $className.$method (${hooked.size} methods)")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: hook $className failed: ${t.message}")
            }
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val injectEntry = lpparam.packageName == "com.android.settings"
        Log.e(
            TAG,
            "SettingsHook.load pkg=${lpparam.packageName} pid=${Process.myPid()} injectEntry=$injectEntry"
        )
        try {
            if (injectEntry) {
                val topLevelClassNames = listOf(
                    "com.android.settings.homepage.TopLevelSettings",
                    "com.android.settings.dashboard.DashboardFragment"
                )
                for (className in topLevelClassNames) {
                    try {
                        val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                        XposedBridge.hookAllMethods(clazz, "onCreatePreferences", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    if (SettingsScreenBuilder.tryBuild(param.thisObject)) return
                                    SettingsEntryInjector.inject(param.thisObject)
                                } catch (t: Throwable) {
                                    XposedBridge.log("$TAG Settings insert: ${t.message}")
                                }
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }

            if (!injectEntry) {
                hookOplusChannelNotificationActivity(lpparam)
                hookOplusNotificationManagerFragments(lpparam)
            }

            val basePrefFragment = try {
                XposedHelpers.findClass(
                    "androidx.preference.PreferenceFragmentCompat",
                    lpparam.classLoader
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: PreferenceFragmentCompat class not found: ${t.message}")
                null
            }
            if (basePrefFragment != null) {
                if (injectEntry) {
                    try {
                        XposedBridge.hookAllMethods(basePrefFragment, "onCreatePreferences", object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    if (SettingsScreenBuilder.tryBuild(param.thisObject)) return
                                    SettingsEntryInjector.inject(param.thisObject)
                                } catch (t: Throwable) {
                                    XposedBridge.log("$TAG Settings insert/baseCreate: ${t.message}")
                                }
                            }
                        })
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: PFC onCreatePreferences hook skipped (abstract?): ${t.message}")
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(
                        basePrefFragment, "onResume",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    runChannelIconHooks(param.thisObject, injectEntry)
                                } catch (t: Throwable) {
                                    XposedBridge.log("$TAG Settings insert/baseResume: ${t.message}")
                                }
                            }
                        }
                    )
                    XposedBridge.log("$TAG: hooked PFC.onResume via findAndHookMethod")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: PFC onResume hook failed: ${t.message}")
                }
            }

            hookConcreteChannelNotificationFragments(lpparam, injectEntry)

            if (injectEntry) {
                try {
                    val anyFragment = XposedHelpers.findClass(
                        "androidx.fragment.app.Fragment",
                        lpparam.classLoader
                    )
                    XposedBridge.hookAllMethods(anyFragment, "onResume", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                SettingsScreenBuilder.tryBuild(param.thisObject)
                            } catch (_: Throwable) {}
                        }
                    })
                } catch (_: Throwable) {}
            }

            // Hook onActivityResult to receive file picker result
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader,
                "onActivityResult",
                Int::class.java, Int::class.java, android.content.Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args[0] as Int
                        if (requestCode == ChannelNotificationIconInjector.FILE_PICKER_REQUEST_CODE) {
                            val resultCode = param.args[1] as Int
                            val data = param.args[2] as? android.content.Intent
                            try {
                                ChannelNotificationIconInjector.handleFilePickerResult(
                                    param.thisObject as android.app.Activity, resultCode, data)
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG handleFilePickerResult: ${t.message}")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: hook onActivityResult failed: ${t.message}")
        }

        hookWidgetIconPreview(lpparam)
        hookFullWidthDivider(lpparam)
        XposedBridge.log("$TAG: Settings hook OK (${lpparam.packageName}) pid=${Process.myPid()}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Settings: ${t.message}")
        }
    }

    /**
     * COUI starts a row's divider at its title view (getDividerStartAlignView), which leaves the
     * icon column of the custom-icons app list undivided. Returning null makes the decoration use
     * the default start inset, so the divider spans the row like on the other screens.
     */
    private fun hookFullWidthDivider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.coui.appcompat.preference.COUIPreference", lpparam.classLoader,
                "getDividerStartAlignView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = try {
                            XposedHelpers.callMethod(param.thisObject, "getKey") as? String
                        } catch (_: Throwable) { null } ?: return
                        if (key.startsWith(SettingsScreenBuilder.CUSTOM_ICONS_ENTRY_KEY_PREFIX)) param.result = null
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * Hook [onBindViewHolder] on COUI/Preference classes to display the icon preview
     * on the RIGHT side (widget frame) instead of the left (setIcon).
     */
    private fun hookWidgetIconPreview(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.coui.appcompat.preference.COUIPreference",
            "com.coui.appcompat.preference.COUITwoTargetPreference",
            "androidx.preference.Preference"
        )
        for (className in candidates) {
            try {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "onBindViewHolder", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = try {
                            XposedHelpers.callMethod(param.thisObject, "getKey") as? String
                        } catch (_: Throwable) { return }
                        val holder = param.args?.getOrNull(0) ?: return
                        val itemView: View = try {
                            XposedHelpers.getObjectField(holder, "itemView") as? View
                        } catch (_: Throwable) {
                            try { XposedHelpers.callMethod(holder, "itemView") as? View } catch (_: Throwable) { null }
                        } ?: return
                        try {
                            val enabled = XposedHelpers.callMethod(param.thisObject, "isEnabled") as? Boolean ?: true
                            // Switch rows: the preview is also dimmed while the row's own switch is off
                            val checked = try {
                                XposedHelpers.callMethod(param.thisObject, "isChecked") as? Boolean
                            } catch (_: Throwable) { null } ?: true
                            SettingsScreenBuilder.bindCustomIconPreview(key, itemView, enabled && checked)
                        } catch (_: Throwable) {}
                        if (key !in ChannelNotificationIconInjector.ICON_PREF_KEYS) return
                        val widgetFrame = try {
                            itemView.findViewById<android.view.ViewGroup>(android.R.id.widget_frame)
                        } catch (_: Throwable) { null } ?: return
                        widgetFrame.removeAllViews()
                        val drawable = ChannelNotificationIconInjector.iconPreviews[key]
                        if (drawable != null) {
                            val density = itemView.context.resources.displayMetrics.density
                            val size = (32 * density).toInt()
                            val iv = android.widget.ImageView(itemView.context)
                            iv.setImageDrawable(drawable)
                            iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            // Notification icons are monochrome (usually black): tint with the
                            // theme text color so the preview stays visible in dark mode
                            try {
                                val a = itemView.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                                iv.imageTintList = a.getColorStateList(0)
                                a.recycle()
                            } catch (_: Throwable) {}
                            widgetFrame.addView(iv, android.view.ViewGroup.LayoutParams(size, size))
                            widgetFrame.visibility = View.VISIBLE
                        } else {
                            widgetFrame.visibility = View.GONE
                        }
                    }
                })
            } catch (_: Throwable) {}
        }
    }
}
