package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

@SuppressLint("PrivateApi")
object SettingsHook {

    private const val TAG = "OxygenOSMaterialIcons"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
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

            try {
                val basePrefFragment = XposedHelpers.findClass(
                    "androidx.preference.PreferenceFragmentCompat",
                    lpparam.classLoader
                )
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
                XposedBridge.hookAllMethods(basePrefFragment, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (SettingsScreenBuilder.tryBuild(param.thisObject)) return
                            SettingsEntryInjector.inject(param.thisObject)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Settings insert/baseResume: ${t.message}")
                        }
                    }
                })
            } catch (_: Throwable) {}

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

            XposedBridge.log("$TAG: Settings hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Settings: ${t.message}")
        }
    }
}
