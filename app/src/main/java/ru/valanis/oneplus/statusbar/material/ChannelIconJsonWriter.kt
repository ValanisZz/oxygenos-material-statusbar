package ru.valanis.oneplus.statusbar.material

import android.content.Context
import org.json.JSONObject
import ru.valanis.oneplus.hook.core.ChannelIconStore

/**
 * Writes the channel icon map ([ChannelIconStore.MAP_URI]) to the module's [IconProvider].
 * Used from the injected preference section in [ru.valanis.oneplus.hook.settings.ChannelNotificationIconInjector].
 */
object ChannelIconJsonWriter {

    fun readMap(context: Context): MutableMap<String, String> =
        ChannelIconStore.readMap(context.contentResolver) ?: mutableMapOf()

    /** Applies [change] to the stored map. False if it couldn't be read or written. */
    fun update(context: Context, change: (MutableMap<String, String>) -> Unit): Boolean {
        return try {
            val resolver = context.contentResolver
            // Never write over a map that couldn't be read: that would drop every other entry
            val map = ChannelIconStore.readMap(resolver) ?: return false
            change(map)
            val json = JSONObject()
            for ((k, v) in map) {
                if (v.isNotEmpty()) json.put(k, v)
            }
            resolver.openOutputStream(ChannelIconStore.MAP_URI, "wt")?.use {
                it.write(json.toString().toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (_: Throwable) {
            false
        }
    }
}
