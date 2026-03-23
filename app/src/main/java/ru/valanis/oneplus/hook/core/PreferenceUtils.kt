package ru.valanis.oneplus.hook.core

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import ru.valanis.oneplus.statusbar.material.settings.ModulePreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object PreferenceUtils {

    const val MODULE_PKG = "ru.valanis.oneplus.statusbar.material"
    private const val TAG = "OxygenOSMaterialIcons"

    const val SETTINGS_GLOBAL_PREFIX = "oxygen_material_"

    const val PREFS_PATH = "/data/system/${MODULE_PKG}_prefs.xml"

    @Deprecated("Old location, kept for migration")
    const val LEGACY_PREFS_PATH = "/data/local/tmp/${MODULE_PKG}_prefs.xml"

    private val KNOWN_KEYS = listOf(
        ModulePreferences.KEY_ENABLE_MODULE,
        ModulePreferences.KEY_ENABLE_STATUS_BAR,
        ModulePreferences.KEY_ENABLE_SHADE,
        ModulePreferences.KEY_ENABLE_AOD,
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.KEY_ENABLE_CUSTOM_ICONS
    )

    private val lastReloadMs = AtomicLong(0L)

    @Volatile
    private var cached = ConcurrentHashMap<String, Boolean>()
    private var loggedOnce = false

    private val xspFallback: XSharedPreferences? by lazy {
        try {
            XSharedPreferences(MODULE_PKG, ModulePreferences.PREF_FILE).also { it.makeWorldReadable() }
        } catch (_: Throwable) { null }
    }

    private val boolRegex = Regex("""<boolean name="([^"]+)" value="([^"]+)"""")

    fun globalKey(prefKey: String): String = "$SETTINGS_GLOBAL_PREFIX$prefKey"

    private fun getSystemResolver(): ContentResolver? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val app = clazz.getMethod("currentApplication").invoke(null) as? Context
            app?.contentResolver
        } catch (_: Throwable) { null }
    }

    private fun reload() {
        val now = System.currentTimeMillis()
        if (now - lastReloadMs.get() < 2000L) return
        lastReloadMs.set(now)

        if (tryLoadFromSettingsGlobal()) return
        if (tryLoadFromFile(PREFS_PATH)) return
        @Suppress("DEPRECATION")
        if (tryLoadFromFile(LEGACY_PREFS_PATH)) return
        tryLoadFromXsp()
    }

    private fun tryLoadFromSettingsGlobal(): Boolean {
        val resolver = getSystemResolver() ?: return false
        try {
            val map = ConcurrentHashMap<String, Boolean>()
            for (key in KNOWN_KEYS) {
                val value = Settings.Global.getInt(resolver, globalKey(key), -1)
                if (value != -1) map[key] = value == 1
            }
            if (map.isNotEmpty()) {
                if (!loggedOnce) {
                    loggedOnce = true
                    XposedBridge.log("$TAG Prefs loaded from Settings.Global: $map")
                }
                cached = map
                return true
            }
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs Settings.Global error: ${t.message}")
        }
        return false
    }

    private fun tryLoadFromFile(path: String): Boolean {
        try {
            val file = File(path)
            if (!file.canRead()) {
                if (!loggedOnce) XposedBridge.log("$TAG Prefs: $path canRead=false")
                return false
            }
            val xml = file.readText()
            val map = ConcurrentHashMap<String, Boolean>()
            for (match in boolRegex.findAll(xml)) {
                map[match.groupValues[1]] = match.groupValues[2].toBoolean()
            }
            if (map.isNotEmpty()) {
                if (!loggedOnce) {
                    loggedOnce = true
                    XposedBridge.log("$TAG Prefs loaded from $path: $map")
                }
                cached = map
                return true
            }
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs error($path): ${t.message}")
        }
        return false
    }

    private fun tryLoadFromXsp() {
        try {
            val xsp = xspFallback ?: return
            xsp.reload()
            if (xsp.all.isEmpty()) return
            val map = ConcurrentHashMap<String, Boolean>()
            for ((key, value) in xsp.all) {
                if (value is Boolean) map[key] = value
            }
            if (map.isNotEmpty()) {
                if (!loggedOnce) {
                    loggedOnce = true
                    XposedBridge.log("$TAG Prefs loaded from XSP: $map")
                }
                cached = map
            }
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs XSP error: ${t.message}")
        }
    }

    fun prefBoolean(key: String, defaultValue: Boolean): Boolean {
        reload()
        return cached[key] ?: defaultValue
    }

    fun isModuleEnabled(): Boolean = prefBoolean(
        ModulePreferences.KEY_ENABLE_MODULE,
        ModulePreferences.DEFAULT_ENABLE_MODULE
    )

    fun isStatusBarEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_STATUS_BAR,
        ModulePreferences.DEFAULT_ENABLE_STATUS_BAR
    )

    fun isShadeEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_SHADE,
        ModulePreferences.DEFAULT_ENABLE_SHADE
    )

    fun isAodEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_AOD,
        ModulePreferences.DEFAULT_ENABLE_AOD
    )

    fun isCustomIconsEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_CUSTOM_ICONS,
        ModulePreferences.DEFAULT_ENABLE_CUSTOM_ICONS
    )
}
