package ru.valanis.oneplus.statusbar.material

import org.json.JSONObject
import ru.valanis.oneplus.hook.core.ChannelIconStore
import java.io.File

/**
 * Writes channel icon map to [ChannelIconStore.CHANNEL_ICONS_PATH] via root when needed.
 * Used from the injected preference section in [ru.valanis.oneplus.hook.settings.ChannelNotificationIconInjector].
 */
object ChannelIconJsonWriter {

    fun readMapFromDisk(): MutableMap<String, String> {
        val path = ChannelIconStore.CHANNEL_ICONS_PATH
        readJsonText(path)?.let { text ->
            return parseJsonToMap(text)
        }
        return mutableMapOf()
    }

    private fun readJsonText(path: String): String? {
        try {
            val f = File(path)
            if (f.canRead()) return f.readText()
        } catch (_: Throwable) {}
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path' 2>/dev/null"))
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseJsonToMap(text: String): MutableMap<String, String> {
        return try {
            val json = JSONObject(text)
            val out = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    fun writeMapToDisk(map: Map<String, String>): Boolean {
        val json = JSONObject()
        for ((k, v) in map) {
            if (v.isNotEmpty()) json.put(k, v)
        }
        val content = json.toString()
        val path = ChannelIconStore.CHANNEL_ICONS_PATH
        return try {
            val f = File(path)
            if (f.parentFile?.exists() != true) {
                // try direct write (unlikely for /data/system)
            }
            f.writeText(content)
            @Suppress("DEPRECATION")
            f.setReadable(true, false)
            true
        } catch (_: Throwable) {
            writeViaSu(path, content)
        }
    }

    private fun writeViaSu(path: String, content: String): Boolean {
        return try {
            val process = ProcessBuilder(
                "su", "-c",
                "cat > '$path' && chmod 644 '$path'"
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(content) }
            process.waitFor()
            process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }
}
