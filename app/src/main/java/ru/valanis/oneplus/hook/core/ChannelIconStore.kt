package ru.valanis.oneplus.hook.core

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import ru.valanis.oneplus.statusbar.material.IconProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps (package + channelId) to an icon URI. The map and the icons are files of the module
 * served by [IconProvider], so no process needs root or storage access.
 */
object ChannelIconStore {

    private const val TAG = "OxygenOSMaterialIcons"

    val MAP_URI: Uri = IconProvider.uri(IconProvider.FILE_MAP)

    const val KEY_SEPARATOR = "\u0001"
    const val MONO_SUFFIX  = "${KEY_SEPARATOR}mono"
    const val SHADE_SUFFIX = "${KEY_SEPARATOR}shade"
    const val AOD_SUFFIX   = "${KEY_SEPARATOR}aod"

    @Volatile
    private var dirty = true
    @Volatile
    private var loaded = false
    @Volatile
    private var packages: Set<String> = emptySet()
    private val lastAttemptMs = AtomicLong(0L)
    private val observing = AtomicBoolean(false)
    private val cached = ConcurrentHashMap<String, String>()
    private var loggedOnce = false

    // Provider reads are IPC (and may start the module process) — never on the calling
    // thread: hooks run on the SystemUI main thread
    private val reloadPending = AtomicBoolean(false)
    private val reloadExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "OxygenMaterialChIcons").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    fun storeKey(packageName: String, channelId: String): String =
        "$packageName$KEY_SEPARATOR$channelId"

    fun reload() {
        // Watched map: re-read only after a change. Unwatched or unreachable: retry every 10 s
        if (!dirty && observing.get()) return
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs.get() < 10_000L) return
        if (!reloadPending.compareAndSet(false, true)) return
        lastAttemptMs.set(now)
        reloadExecutor.execute {
            try {
                val resolver = PreferenceUtils.systemResolver() ?: return@execute
                observe(resolver)
                // Cleared before the read: a change notified during it marks the map dirty again
                dirty = false
                val map = readMap(resolver)
                if (map == null) {
                    dirty = true
                    if (!loggedOnce) {
                        loggedOnce = true
                        XposedBridge.log("$TAG ChannelIcons: $MAP_URI not readable")
                    }
                    return@execute
                }
                val changed = !loaded || cached != map
                cached.keys.retainAll(map.keys)
                cached.putAll(map)
                packages = map.keys.mapTo(HashSet()) { it.substringBefore(KEY_SEPARATOR) }
                loaded = true
                IconUtils.preloadChannelIcons(map.values)
                if (changed) IconUtils.onChannelMapChanged()
                if (!loggedOnce) {
                    loggedOnce = true
                    XposedBridge.log("$TAG ChannelIcons loaded (${map.size} entries)")
                }
            } catch (_: Throwable) {
                dirty = true
            } finally {
                reloadPending.set(false)
            }
        }
    }

    private fun observe(resolver: ContentResolver) {
        if (!observing.compareAndSet(false, true)) return
        try {
            resolver.registerContentObserver(MAP_URI, false, object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    dirty = true
                    lastAttemptMs.set(0L)
                    // Pick the change up now, not on the next icon lookup
                    reload()
                }
            })
        } catch (t: Throwable) {
            observing.set(false)
            XposedBridge.log("$TAG ChannelIcons observer: ${t.message}")
        }
    }

    /** Current map from the module, or null when the provider can't be reached. */
    fun readMap(resolver: ContentResolver): MutableMap<String, String>? {
        return try {
            val text = resolver.openInputStream(MAP_URI)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return null
            val json = JSONObject(text)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotEmpty()) map[k] = v
            }
            map
        } catch (_: Throwable) {
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

    /** True if any icon override (main/shade/aod/mono) is configured for [packageName]. */
    fun hasPackage(packageName: String): Boolean {
        reload()
        return packageName in packages
    }

    /** False until the map has been read once: lookups before that prove nothing. */
    fun isLoaded(): Boolean = loaded

    fun cachedSize(): Int = cached.size

    fun invalidateCache() {
        dirty = true
        lastAttemptMs.set(0L)
        loggedOnce = false
    }
}
