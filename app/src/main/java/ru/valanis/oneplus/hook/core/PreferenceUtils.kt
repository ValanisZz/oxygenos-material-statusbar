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

        /**
         * Раньше при любом непустом Settings.Global делали return и **не читали** XML модуля.
         * В Global часто есть только часть ключей → остальные брали default (у категорийных иконок false)
         * и инъекция в `com.oplus.notificationmanager` **молча не работала**.
         * Сейчас: база из файла/XSP, поверх — оверлей Global.
         */
        val merged = ConcurrentHashMap<String, Boolean>()
        tryLoadFromFileInto(PREFS_PATH, merged)
        if (merged.isEmpty()) {
            @Suppress("DEPRECATION")
            tryLoadFromFileInto(LEGACY_PREFS_PATH, merged)
        }
        if (merged.isEmpty()) {
            tryLoadFromXspInto(merged)
        }
        mergeSettingsGlobalInto(merged)
        if (merged.isNotEmpty()) {
            if (!loggedOnce) {
                loggedOnce = true
                XposedBridge.log("$TAG Prefs merged (file/XSP + Global overlay): $merged")
            }
            cached = merged
        } else {
            tryLoadFromSettingsGlobalOnly()
        }
    }

    /** Только Global — если файла/XSP нет (редко). */
    private fun tryLoadFromSettingsGlobalOnly(): Boolean {
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
                    XposedBridge.log("$TAG Prefs loaded from Settings.Global only: $map")
                }
                cached = map
                return true
            }
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs Settings.Global error: ${t.message}")
        }
        return false
    }

    private fun mergeSettingsGlobalInto(map: ConcurrentHashMap<String, Boolean>) {
        val resolver = getSystemResolver() ?: return
        try {
            for (key in KNOWN_KEYS) {
                val value = Settings.Global.getInt(resolver, globalKey(key), -1)
                if (value != -1) map[key] = value == 1
            }
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs Settings.Global merge: ${t.message}")
        }
    }

    private fun parseBoolXmlInto(xml: String, target: ConcurrentHashMap<String, Boolean>) {
        for (match in boolRegex.findAll(xml)) {
            target[match.groupValues[1]] = match.groupValues[2].toBoolean()
        }
    }

    private fun tryLoadFromFileInto(path: String, target: ConcurrentHashMap<String, Boolean>): Boolean {
        try {
            val file = File(path)
            val xml = if (file.canRead()) {
                file.readText()
            } else {
                if (!loggedOnce) XposedBridge.log("$TAG Prefs: $path canRead=false")
                readPrefsFileViaSu(path) ?: return false
            }
            parseBoolXmlInto(xml, target)
            if (target.isNotEmpty()) return true
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG Prefs error($path): ${t.message}")
        }
        return readPrefsFileViaSu(path)?.let { suXml ->
            parseBoolXmlInto(suXml, target)
            target.isNotEmpty()
        } ?: false
    }

    /** Как [ru.valanis.oneplus.hook.settings.SettingsScreenBuilder.readViaSu] — чтение prefs с root. */
    private fun readPrefsFileViaSu(path: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cat '$path' 2>/dev/null")
            )
            val xml = process.inputStream.bufferedReader().readText()
            process.waitFor()
            xml.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryLoadFromXspInto(target: ConcurrentHashMap<String, Boolean>) {
        try {
            val xsp = xspFallback ?: return
            xsp.reload()
            if (xsp.all.isEmpty()) return
            for ((key, value) in xsp.all) {
                if (value is Boolean) target[key] = value
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

    fun isNotificationCategoryIconsEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.DEFAULT_ENABLE_NOTIFICATION_CATEGORY_ICONS
    )

}
