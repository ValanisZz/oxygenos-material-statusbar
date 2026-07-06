package ru.valanis.oneplus.hook.core

import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps (package + channelId) to absolute path of a Vector Drawable XML file.
 * Backed by JSON on disk (same visibility pattern as [PreferenceUtils.PREFS_PATH]).
 */
object ChannelIconStore {

    private const val TAG = "OxygenOSMaterialIcons"

    const val CHANNEL_ICONS_PATH = "/data/system/${PreferenceUtils.MODULE_PKG}_channel_icons.json"

    const val KEY_SEPARATOR = "\u0001"
    const val MONO_SUFFIX  = "${KEY_SEPARATOR}mono"
    const val SHADE_SUFFIX = "${KEY_SEPARATOR}shade"
    const val AOD_SUFFIX   = "${KEY_SEPARATOR}aod"

    private val lastReloadMs = AtomicLong(0L)
    private val lastKnownFileModMs = AtomicLong(-1L)
    private val cached = ConcurrentHashMap<String, String>()
    private var loggedOnce = false

    fun storeKey(packageName: String, channelId: String): String =
        "$packageName$KEY_SEPARATOR$channelId"

    fun reload() {
        val now = System.currentTimeMillis()
        val lastReload = lastReloadMs.get()
        // Re-read if either 2 s have passed OR the file was modified since last read.
        val fileModMs = try { File(CHANNEL_ICONS_PATH).lastModified() } catch (_: Throwable) { -1L }
        val fileChanged = fileModMs > 0L && fileModMs != lastKnownFileModMs.get()
        if (!fileChanged && now - lastReload < 2000L) return
        lastReloadMs.set(now)
        val loaded = tryLoadFromFile(CHANNEL_ICONS_PATH)
        if (loaded) {
            lastKnownFileModMs.set(fileModMs)
        } else if (!loggedOnce) {
            loggedOnce = true
            XposedBridge.log("$TAG ChannelIcons: failed to load from $CHANNEL_ICONS_PATH (size=${cached.size})")
        }
    }

    private fun tryLoadFromFile(path: String): Boolean {
        val text = readJsonText(path) ?: return false
        return try {
            val json = JSONObject(text)
            val map = ConcurrentHashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotEmpty()) map[k] = v
            }
            if (!loggedOnce) {
                loggedOnce = true
                XposedBridge.log("$TAG ChannelIcons loaded from $path (${map.size} entries)")
            }
            cached.clear()
            cached.putAll(map)
            true
        } catch (t: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG ChannelIcons error: ${t.message}")
            false
        }
    }

    private fun readJsonText(path: String): String? {
        try {
            val file = File(path)
            if (file.canRead()) return file.readText()
        } catch (_: Throwable) {}
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cat '$path' 2>/dev/null")
            )
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            if (!loggedOnce) XposedBridge.log("$TAG ChannelIcons: $path not readable (direct + su)")
            null
        }
    }

    fun getPath(packageName: String, channelId: String): String? {
        reload()
        return cached[storeKey(packageName, channelId)]
    }

    fun isMonochrome(packageName: String, channelId: String): Boolean {
        reload()
        return cached["${storeKey(packageName, channelId)}$MONO_SUFFIX"] == "true"
    }

    /** Returns the shade-specific icon path, falling back to the main icon path. */
    fun getShadePath(packageName: String, channelId: String): String? {
        reload()
        val key = storeKey(packageName, channelId)
        return cached["$key$SHADE_SUFFIX"] ?: cached[key]
    }

    /** Returns the AOD-specific icon path, falling back to the main icon path. */
    fun getAodPath(packageName: String, channelId: String): String? {
        reload()
        val key = storeKey(packageName, channelId)
        return cached["$key$AOD_SUFFIX"] ?: cached[key]
    }

    fun cachedSize(): Int = cached.size

    fun invalidateCache() {
        lastReloadMs.set(0L)
        loggedOnce = false
        cached.clear()
    }
}
