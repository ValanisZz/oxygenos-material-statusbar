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

    // No UI: `adb shell settings put global oxygen_material_debug_log 1` (0 to stop).
    // Logs to the LSPosed log which icon each notification got and why.
    private const val KEY_DEBUG_LOG = "debug_log"

    const val PREFS_PATH = "/data/system/${MODULE_PKG}_prefs.xml"

    @Deprecated("Old location, kept for migration")
    const val LEGACY_PREFS_PATH = "/data/local/tmp/${MODULE_PKG}_prefs.xml"

    private val KNOWN_KEYS = listOf(
        ModulePreferences.KEY_ENABLE_MODULE,
        ModulePreferences.KEY_ENABLE_STATUS_BAR,
        ModulePreferences.KEY_ENABLE_SHADE,
        ModulePreferences.KEY_ENABLE_AOD,
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.KEY_ENABLE_CUSTOM_ICONS,
        KEY_DEBUG_LOG
    )

    private val lastReloadMs = AtomicLong(0L)
    private val lastFileAttemptMs = AtomicLong(0L)
    private val lastFileModMs = AtomicLong(-1L)

    @Volatile
    private var fileSnapshot: Map<String, Boolean>? = null

    @Volatile
    private var cached = ConcurrentHashMap<String, Boolean>()
    private var loggedOnce = false

    @Volatile
    private var dirty = true
    @Volatile
    private var loaded = false
    private val observing = java.util.concurrent.atomic.AtomicBoolean(false)

    // Reloads may read files via `su` (SystemUI can't read /data/system directly) —
    // never on the calling thread: hooks run on the SystemUI main thread
    private val reloadPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reloadExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "OxygenMaterialPrefs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    private val xspFallback: XSharedPreferences? by lazy {
        try {
            XSharedPreferences(MODULE_PKG, ModulePreferences.PREF_FILE).also { it.makeWorldReadable() }
        } catch (_: Throwable) { null }
    }

    private val boolRegex = Regex("""<boolean name="([^"]+)" value="([^"]+)"""")

    fun globalKey(prefKey: String): String = "$SETTINGS_GLOBAL_PREFIX$prefKey"

    fun systemResolver(): ContentResolver? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val app = clazz.getMethod("currentApplication").invoke(null) as? Context
            app?.contentResolver
        } catch (_: Throwable) { null }
    }

    private fun reload() {
        // Watched Settings.Global: changes arrive through the observer, the slow poll is only
        // a safety net (a read made before the settings provider is up returns nothing).
        // Not watched yet (no context this early in the process): poll every 2 s
        val now = System.currentTimeMillis()
        val interval = if (!dirty && observing.get()) 60_000L else 2000L
        if (now - lastReloadMs.get() < interval) return
        lastReloadMs.set(now)
        if (!reloadPending.compareAndSet(false, true)) return
        reloadExecutor.execute {
            try {
                doReload()
            } catch (_: Throwable) {
            } finally {
                reloadPending.set(false)
            }
        }
    }

    private fun observe(resolver: ContentResolver) {
        if (!observing.compareAndSet(false, true)) return
        try {
            resolver.registerContentObserver(
                Settings.Global.CONTENT_URI, true,
                object : android.database.ContentObserver(null) {
                    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                        if (uri != null && uri.lastPathSegment?.startsWith(SETTINGS_GLOBAL_PREFIX) != true) return
                        dirty = true
                        lastReloadMs.set(0L)
                        // Per-app / per-category keys are read live: memoized icons are stale now
                        IconUtils.configGeneration.incrementAndGet()
                        reload()
                    }
                }
            )
        } catch (t: Throwable) {
            observing.set(false)
            XposedBridge.log("$TAG Prefs observer: ${t.message}")
        }
    }

    /** False until the prefs have been read once: until then every switch reports its default. */
    fun isLoaded(): Boolean = loaded

    /** Starts the first load without waiting for a hook to ask. */
    fun warmUp() = reload()

    private fun doReload() {
        val resolver = systemResolver()
        resolver?.let { observe(it) }
        // Cleared before the read: a change notified during it marks the prefs dirty again
        dirty = false
        /**
         * Раньше при любом непустом Settings.Global делали return и **не читали** XML модуля.
         * В Global часто есть только часть ключей → остальные брали default (у категорийных иконок false)
         * и инъекция в `com.oplus.notificationmanager` **молча не работала**.
         * Сейчас: база из файла/XSP, поверх — оверлей Global.
         */
        val merged = ConcurrentHashMap<String, Boolean>()
        val modMs = try { File(PREFS_PATH).lastModified() } catch (_: Throwable) { 0L }
        val snapshot = fileSnapshot
        val unchanged = modMs == lastFileModMs.get()
        // Re-read the file only when its mtime changed. Missing/unstatable files mean
        // the read below may spawn `su` — that is tried once per process, never on a timer
        // (settings toggles propagate via the Settings.Global overlay anyway).
        val skipFileRead = when {
            unchanged && snapshot != null && modMs > 0L -> true
            unchanged && lastFileAttemptMs.get() != 0L -> true
            else -> false
        }
        if (skipFileRead) {
            snapshot?.let { merged.putAll(it) }
        } else {
            lastFileAttemptMs.set(System.currentTimeMillis())
            lastFileModMs.set(modMs)
            tryLoadFromFileInto(PREFS_PATH, merged)
            if (merged.isEmpty()) {
                @Suppress("DEPRECATION")
                tryLoadFromFileInto(LEGACY_PREFS_PATH, merged)
            }
            if (merged.isEmpty()) {
                tryLoadFromXspInto(merged)
            }
            if (merged.isNotEmpty()) fileSnapshot = HashMap(merged)
        }
        mergeSettingsGlobalInto(merged)
        if (merged.isNotEmpty()) {
            if (!loggedOnce) {
                loggedOnce = true
                XposedBridge.log("$TAG Prefs merged (file/XSP + Global overlay): $merged")
            }
            if (merged != cached) {
                cached = merged
                IconUtils.configGeneration.incrementAndGet()
            }
        } else {
            tryLoadFromSettingsGlobalOnly()
        }
        // Without a context Settings.Global was not read: stay dirty and retry
        if (resolver != null) loaded = true else dirty = true
    }

    /** Только Global — если файла/XSP нет (редко). */
    private fun tryLoadFromSettingsGlobalOnly(): Boolean {
        val resolver = systemResolver() ?: return false
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
        val resolver = systemResolver() ?: return
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

    fun isCustomIconEnabled(pkg: String, channelId: String): Boolean =
        dynamicBoolean(ModulePreferences.customIconAppKey(pkg)) &&
            dynamicBoolean(ModulePreferences.customIconChannelKey(pkg, channelId))

    // Per-app / per-category keys are dynamic, so the reload overlay (KNOWN_KEYS) doesn't cover
    // them: read Settings.Global directly (cached client-side by the framework), then the file.
    private fun dynamicBoolean(key: String): Boolean {
        val resolver = systemResolver()
        val v = try { resolver?.let { Settings.Global.getInt(it, globalKey(key), -1) } ?: -1 } catch (_: Throwable) { -1 }
        return if (v != -1) v == 1 else prefBoolean(key, ModulePreferences.DEFAULT_ENABLE_CUSTOM_ICON)
    }

    fun isDebugLog(): Boolean = prefBoolean(KEY_DEBUG_LOG, false)

    fun isNotificationCategoryIconsEnabled(): Boolean = isModuleEnabled() && prefBoolean(
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.DEFAULT_ENABLE_NOTIFICATION_CATEGORY_ICONS
    )

}
