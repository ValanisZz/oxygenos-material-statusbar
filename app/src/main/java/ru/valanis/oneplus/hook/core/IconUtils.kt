package ru.valanis.oneplus.hook.core

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.scale
import de.robv.android.xposed.XposedHelpers
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import ru.valanis.oneplus.statusbar.material.R
import java.io.File
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object IconUtils {

    const val EXTRAS_ORIGINAL_ICON = "oxygen_material_orig_icon"

    private const val MAX_MONOCHROME_CACHE = 100
    private const val MAX_FIT_CACHE = 80
    private const val MAX_RAW_ICON_CACHE = 50
    const val MAX_DESCRIPTOR_CACHE = 100

    private val CUSTOM_SMALL_ICONS = mapOf(
        "com.android.mms" to R.drawable.ic_notify_mms,
        "com.oplus.battery" to R.drawable.ic_notify_battery_full,
        "com.oplus.phonemanager" to R.drawable.ic_notify_phonemanager,
        "com.oneplus.deskclock" to R.drawable.ic_notify_deskclock,
        "com.android.providers.downloads" to R.drawable.ic_notify_download,
        "com.android.providers.donwloads" to R.drawable.ic_notify_download,
        "com.oplus.ota" to R.drawable.ic_notify_ota,
    )

    val iconCache = ConcurrentHashMap<String, Icon>()
    val monochromeCache = ConcurrentHashMap<String, Drawable>()
    private val fitCache = ConcurrentHashMap<String, Drawable>()
    private val rawIconCache = ConcurrentHashMap<String, Drawable>()
    val descriptorCache = ConcurrentHashMap<String, Icon>()

    private val channelDrawableByPath = ConcurrentHashMap<String, Drawable>()

    private fun applicationContextOrNull(): Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            clazz.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    fun clearAllCaches() {
        iconCache.clear()
        monochromeCache.clear()
        fitCache.clear()
        rawIconCache.clear()
        descriptorCache.clear()
        channelDrawableByPath.clear()
        ChannelIconStore.invalidateCache()
    }

    /**
     * Clears all bitmap-keyed cache entries for [pkg] so a freshly patched channel-override
     * icon is rendered correctly instead of stale monochrome / fitted variants.
     * Called from [ru.valanis.oneplus.hook.systemui.AodHook.tryEarlyPatch] after patching.
     */
    fun clearPackageBitmapCaches(pkg: String) {
        val prefix = "fallback_$pkg"
        monochromeCache.keys.removeIf { it.startsWith(prefix) }
        fitCache.keys.removeIf { it.contains(prefix) }
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * Reads file content with su fallback (SystemUI can't read /sdcard directly due to SELinux).
     */
    private fun readFileBytes(path: String): ByteArray? {
        try {
            val file = File(path)
            if (file.canRead()) return file.readBytes()
            android.util.Log.e("OxygenOSMaterialIcons", "readFileBytes: canRead=false path=$path")
        } catch (_: Throwable) {}
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cat '$path' 2>/dev/null")
            )
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            val result = bytes.takeIf { it.isNotEmpty() }
            if (result == null) android.util.Log.e("OxygenOSMaterialIcons", "readFileBytes: su empty for $path")
            result
        } catch (t: Throwable) {
            android.util.Log.e("OxygenOSMaterialIcons", "readFileBytes: su failed for $path: ${t.message}")
            null
        }
    }

    /**
     * Loads a drawable from an on-disk file (user-provided path).
     * Supports VectorDrawable XML and bitmap formats (PNG, WebP, JPG).
     */
    fun loadDrawableFromXmlPath(path: String, context: Context): Drawable? {
        return try {
            val file = File(path)
            val cacheKey = "${path}:${file.lastModified()}"
            channelDrawableByPath[cacheKey]?.let { d ->
                return d.constantState?.newDrawable()?.mutate() ?: d
            }
            val bytes = readFileBytes(path) ?: return null
            val drawable = when {
                path.endsWith(".xml", ignoreCase = true) ->
                    parseVectorDrawableXml(String(bytes, Charsets.UTF_8), context)
                path.endsWith(".svg", ignoreCase = true) ->
                    parseSvgXml(String(bytes, Charsets.UTF_8), context)
                else -> {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return null
                    BitmapDrawable(context.resources, bmp)
                }
            }
            if (drawable == null) return null
            val mutable = drawable.mutate()
            evictIfNeeded(channelDrawableByPath, 40)
            channelDrawableByPath[cacheKey] = mutable
            mutable.constantState?.newDrawable()?.mutate() ?: mutable
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Parses a VectorDrawable text XML and renders it to a Bitmap.
     * Drawable.createFromXml() doesn't work with plain text XML because VectorDrawable
     * requires compiled (binary) XML with resolved attribute resource IDs.
     */
    private fun parseVectorDrawableXml(xmlContent: String, context: Context): Drawable? {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xmlContent))

        var viewportW = 24f
        var viewportH = 24f
        val paths = mutableListOf<Triple<Path, Paint, Paint?>>() // path, fill, stroke

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "vector" -> {
                    viewportW = parser.getAttributeValue(ANDROID_NS, "viewportWidth")?.toFloatOrNull() ?: 24f
                    viewportH = parser.getAttributeValue(ANDROID_NS, "viewportHeight")?.toFloatOrNull() ?: 24f
                }
                "path" -> {
                    val pathData = parser.getAttributeValue(ANDROID_NS, "pathData") ?: continue
                    val path = createPathFromData(pathData) ?: continue
                    val fillColorStr = parser.getAttributeValue(ANDROID_NS, "fillColor")
                    val strokeColorStr = parser.getAttributeValue(ANDROID_NS, "strokeColor")
                    val strokeWidthStr = parser.getAttributeValue(ANDROID_NS, "strokeWidth")

                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = parseColorValue(fillColorStr) ?: Color.BLACK
                    }
                    val strokePaint = if (strokeColorStr != null) {
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            color = parseColorValue(strokeColorStr) ?: Color.TRANSPARENT
                            strokeWidth = strokeWidthStr?.toFloatOrNull() ?: 1f
                        }
                    } else null
                    paths.add(Triple(path, fillPaint, strokePaint))
                }
            }
        }

        if (paths.isEmpty()) return null

        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(48)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.scale(size / viewportW, size / viewportH)
        for ((path, fill, stroke) in paths) {
            canvas.drawPath(path, fill)
            if (stroke != null) canvas.drawPath(path, stroke)
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Minimal SVG parser supporting `<path d="...">` elements with fill/stroke attributes.
     * SVG path data (`d`) uses the same syntax as Android VectorDrawable `pathData`,
     * so we reuse PathParser. Groups (`<g>`) are traversed but transforms are ignored.
     */
    private fun parseSvgXml(xmlContent: String, context: Context): Drawable? {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xmlContent))

        var viewportW = 24f
        var viewportH = 24f
        val paths = mutableListOf<Triple<Path, Paint, Paint?>>()

        // Inherit fill from ancestor <g> elements; SVG default fill is black
        val fillStack = ArrayDeque<String?>()
        fillStack.addLast(null) // root default: black

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "svg" -> {
                        val viewBox = parser.getAttributeValue(null, "viewBox")
                        if (viewBox != null) {
                            val parts = viewBox.trim().split(Regex("[,\\s]+"))
                            if (parts.size >= 4) {
                                viewportW = parts[2].toFloatOrNull() ?: 24f
                                viewportH = parts[3].toFloatOrNull() ?: 24f
                            }
                        } else {
                            viewportW = parser.getAttributeValue(null, "width")
                                ?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() ?: 24f
                            viewportH = parser.getAttributeValue(null, "height")
                                ?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() ?: 24f
                        }
                    }
                    "g" -> {
                        // Push inherited fill from group (null = inherit from parent)
                        val gFill = parser.getAttributeValue(null, "fill")
                            ?: parseSvgStyleAttr(parser.getAttributeValue(null, "style") ?: "")["fill"]
                        fillStack.addLast(gFill)
                    }
                    "path" -> {
                        val d = parser.getAttributeValue(null, "d") ?: continue
                        val svgPath = createPathFromData(d) ?: continue

                        val styleMap = parseSvgStyleAttr(parser.getAttributeValue(null, "style") ?: "")
                        val fillStr = styleMap["fill"]
                            ?: parser.getAttributeValue(null, "fill")
                            ?: fillStack.lastOrNull { it != null } // inherit from g ancestors
                        val strokeStr = styleMap["stroke"] ?: parser.getAttributeValue(null, "stroke")
                        val strokeWidthStr = styleMap["stroke-width"] ?: parser.getAttributeValue(null, "stroke-width")

                        val fillColor = when {
                            fillStr == "none" -> null
                            fillStr != null -> parseSvgColor(fillStr)
                            else -> Color.BLACK // SVG default
                        }
                        val strokeColor = when {
                            strokeStr == "none" || strokeStr == null -> null
                            else -> parseSvgColor(strokeStr)
                        }

                        if (fillColor != null || strokeColor != null) {
                            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.FILL
                                color = fillColor ?: Color.TRANSPARENT
                            }
                            val strokePaint = strokeColor?.let {
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE
                                    color = it
                                    strokeWidth = strokeWidthStr?.replace(Regex("[^0-9.]"), "")
                                        ?.toFloatOrNull() ?: 1f
                                }
                            }
                            paths.add(Triple(svgPath, fillPaint, strokePaint))
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "g") {
                    if (fillStack.size > 1) fillStack.removeLast()
                }
            }
        }

        if (paths.isEmpty()) {
            android.util.Log.e("OxygenOSMaterialIcons", "parseSvgXml: no renderable paths found (vp=${viewportW}x${viewportH})")
            return null
        }

        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(48)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.scale(size / viewportW, size / viewportH)
        for ((path, fill, stroke) in paths) {
            if (fill.color != Color.TRANSPARENT) canvas.drawPath(path, fill)
            if (stroke != null) canvas.drawPath(path, stroke)
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun parseSvgStyleAttr(style: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (decl in style.split(';')) {
            val parts = decl.trim().split(':', limit = 2)
            if (parts.size == 2) map[parts[0].trim().lowercase()] = parts[1].trim()
        }
        return map
    }

    private fun parseSvgColor(colorStr: String): Int? =
        try { Color.parseColor(colorStr.trim()) } catch (_: Throwable) { null }

    private fun createPathFromData(pathData: String): Path? {
        return try {
            val parserClass = Class.forName("android.util.PathParser")
            parserClass.getMethod("createPathFromPathData", String::class.java)
                .invoke(null, pathData) as? Path
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseColorValue(colorStr: String?): Int? {
        if (colorStr.isNullOrEmpty()) return null
        return try {
            Color.parseColor(colorStr)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * @param variant "main" (status bar), "shade" (notification shade), or "aod".
     *   Shade and AOD fall back to the main icon if no variant-specific path is stored.
     */
    fun channelOverrideIcon(
        pkg: String,
        channelId: String,
        context: Context,
        variant: String = "main"
    ): Icon? {
        if (!PreferenceUtils.isNotificationCategoryIconsEnabled()) return null
        if (channelId.isEmpty()) return null
        val path = when (variant) {
            "shade" -> ChannelIconStore.getShadePath(pkg, channelId)
            "aod"   -> ChannelIconStore.getAodPath(pkg, channelId)
            else    -> { ChannelIconStore.reload(); ChannelIconStore.getPath(pkg, channelId) }
        } ?: return null
        val d = loadDrawableFromXmlPath(path, context)
        if (d == null) {
            android.util.Log.e("OxygenOSMaterialIcons", "channelOverrideIcon[$pkg/$channelId/$variant]: drawable=null path=$path")
            return null
        }
        return Icon.createWithBitmap(d.toBitmap())
    }

    fun customSmallIcon(pkg: String, notification: Notification? = null, context: Context? = null): Icon? {
        if (!PreferenceUtils.isModuleEnabled()) return null

        val resId = CUSTOM_SMALL_ICONS[pkg]
        if (resId != null) return Icon.createWithResource(PreferenceUtils.MODULE_PKG, resId)

        if (notification != null) {
            val channelId = try { notification.channelId } catch (_: Throwable) { null } ?: ""
            val category = try { notification.category } catch (_: Throwable) { null }

            if (pkg == "com.android.systemui") {
                val isBattery = channelId.contains("battery", ignoreCase = true) ||
                    channelId.contains("bat", ignoreCase = true) ||
                    channelId.contains("CRG", ignoreCase = true) ||
                    channelId.contains("charge", ignoreCase = true) ||
                    channelId.contains("power", ignoreCase = true) ||
                    channelId.contains("low", ignoreCase = true) ||
                    (category != null && category.contains("battery", ignoreCase = true))
                if (isBattery) {
                    return Icon.createWithResource(PreferenceUtils.MODULE_PKG, R.drawable.ic_notify_battery_full)
                }
            }

            if (category == Notification.CATEGORY_MISSED_CALL ||
                channelId.contains("missed", ignoreCase = true)
            ) {
                return Icon.createWithResource(PreferenceUtils.MODULE_PKG, R.drawable.ic_notify_missed_call)
            }
        }

        return null
    }

    fun getIconCacheKey(icon: Icon, pkg: String): String? {
        try {
            val type = XposedHelpers.getIntField(icon, "mType")
            if (type != 2) return null
            val resId = try {
                icon.javaClass.getDeclaredMethod("getResId").apply { isAccessible = true }.invoke(icon) as Int
            } catch (_: Throwable) { XposedHelpers.getIntField(icon, "mInt1") }
            if (resId == 0) return null
            val resPkg = try {
                icon.javaClass.getDeclaredMethod("getResPackage").apply { isAccessible = true }.invoke(icon) as? String
            } catch (_: Throwable) { try { XposedHelpers.getObjectField(icon, "mString1") as? String } catch (_: Throwable) { null } } ?: pkg
            return "r_${resPkg}_$resId"
        } catch (_: Throwable) { return null }
    }

    fun getCachedMonochrome(icon: Icon?, pkg: String, drawable: Drawable, rawPath: Boolean = false): Drawable {
        val baseKey = icon?.let { getIconCacheKey(it, pkg) } ?: "fallback_$pkg"
        val key = if (rawPath) "${baseKey}_raw" else baseKey
        evictIfNeeded(monochromeCache, MAX_MONOCHROME_CACHE)
        val cached = monochromeCache.getOrPut(key) { ensureMonochrome(drawable) }
        return cached.constantState?.newDrawable()?.mutate() ?: cached
    }

    fun getCachedMonochromeFallback(pkg: String, drawable: Drawable): Drawable {
        return getCachedMonochrome(null, pkg, drawable)
    }

    fun loadIconWithoutTheme(icon: Icon, pkg: String, context: Context): Drawable? {
        try {
            val iconType = XposedHelpers.getIntField(icon, "mType")
            if (iconType != 2) return null

            val resId = try {
                val m = icon.javaClass.getDeclaredMethod("getResId")
                m.isAccessible = true
                m.invoke(icon) as Int
            } catch (_: Throwable) {
                XposedHelpers.getIntField(icon, "mInt1")
            }
            if (resId == 0) return null

            val resPkg = try {
                val m = icon.javaClass.getDeclaredMethod("getResPackage")
                m.isAccessible = true
                m.invoke(icon) as? String
            } catch (_: Throwable) {
                try { XposedHelpers.getObjectField(icon, "mString1") as? String } catch (_: Throwable) { null }
            } ?: pkg

            val key = "raw_${resPkg}_$resId"
            evictIfNeeded(rawIconCache, MAX_RAW_ICON_CACHE)
            rawIconCache[key]?.let { cached ->
                return cached.constantState?.newDrawable()?.mutate() ?: cached
            }
            val pkgCtx = context.createPackageContext(resPkg, Context.CONTEXT_IGNORE_SECURITY)
            @Suppress("DEPRECATION")
            val d = pkgCtx.resources.getDrawable(resId, null) ?: return null
            rawIconCache[key] = d
            return d.constantState?.newDrawable()?.mutate() ?: d
        } catch (_: Throwable) {
            return null
        }
    }

    fun looksLikePackageName(s: String): Boolean {
        if (s.length < 3 || !s[0].isLetter()) return false
        if (!s.contains('.')) return false
        if (s.startsWith("http", ignoreCase = true) || s.startsWith("/") || s.contains("://")) return false
        if (s.contains('/') || s.contains('\\')) return false
        if (s.endsWith(".apk", ignoreCase = true) || s.endsWith(".dex", ignoreCase = true)) return false
        return true
    }

    fun isColoredIcon(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return false

        val step = maxOf(1, minOf(width, height) / 12)
        var coloredPixels = 0
        var totalChecked = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap[x, y]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha > 50) {
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    val saturation = if (mx > 0) (mx - mn).toFloat() / mx else 0f
                    totalChecked++
                    if (saturation > 0.2f) coloredPixels++
                }
                x += step
            }
            y += step
        }

        return totalChecked > 0 && coloredPixels.toFloat() / totalChecked > 0.3f
    }

    fun ensureMonochrome(drawable: Drawable): Drawable {
        if (drawable is AdaptiveIconDrawable) {
            try {
                val monoLayer = drawable.javaClass.getMethod("getMonochrome").invoke(drawable) as? Drawable
                if (monoLayer != null) return toWhiteAlphaMask(monoLayer.toBitmap())
            } catch (_: Throwable) {}

            val fg = drawable.foreground
            if (fg != null) {
                val fgBitmap = fg.toBitmap()
                if (hasTransparency(fgBitmap)) return toWhiteAlphaMask(fgBitmap)
                val silhouette = toForegroundSilhouette(fgBitmap)
                val silBitmap = (silhouette as? BitmapDrawable)?.bitmap
                if (silBitmap != null && !isBadSilhouette(silBitmap)) return silhouette
                return toLuminanceAlpha(fgBitmap)
            }
        }

        val bitmap = drawable.toBitmap()
        val transparent = hasTransparency(bitmap)

        if (transparent) {
            val mask = toWhiteAlphaMask(bitmap)
            val maskBm = mask.toBitmap()
            if (!isBadSilhouette(maskBm)) return mask

            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                pixels[i] = pixels[i] or (0xFF shl 24)
            }
            val opaqueBitmap = createBitmap(w, h)
            opaqueBitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            opaqueBitmap.density = bitmap.density

            val silhouette = toForegroundSilhouette(opaqueBitmap)
            val silBm = (silhouette as? BitmapDrawable)?.bitmap
            if (silBm != null && !isBadSilhouette(silBm)) return silhouette
            val lumAlpha = toLuminanceAlpha(opaqueBitmap)
            val lumBm = lumAlpha.toBitmap()
            if (!isBadSilhouette(lumBm)) return lumAlpha
            return mask
        }

        val silhouette = toForegroundSilhouette(bitmap)
        val silBitmap = (silhouette as? BitmapDrawable)?.bitmap
        if (silBitmap != null && isBadSilhouette(silBitmap)) return toLuminanceAlpha(bitmap)
        return silhouette
    }

    fun fitToSize(drawable: Drawable, targetW: Int, targetH: Int, res: Resources, cacheKey: String? = null): Drawable {
        if (targetW <= 0 || targetH <= 0) return drawable
        val key = cacheKey?.let { "fit_${it}_${targetW}_${targetH}" }
        if (key != null) {
            evictIfNeeded(fitCache, MAX_FIT_CACHE)
            fitCache[key]?.let { cached ->
                return cached.constantState?.newDrawable()?.mutate() ?: cached
            }
        }
        val bitmap = drawable.toBitmap()
        if (bitmap.width == targetW && bitmap.height == targetH) return drawable
        val scaled = bitmap.scale(targetW, targetH)
        scaled.density = bitmap.density
        val result = scaled.toDrawable(res)
        key?.let { fitCache[it] = result }
        return result.constantState?.newDrawable()?.mutate() ?: result
    }

    fun toWhiteSilhouette(drawable: Drawable): Drawable {
        val src = drawable.toBitmap()
        val result = createBitmap(src.width, src.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        result.density = src.density
        return result.toDrawable(Resources.getSystem())
    }

    fun <K, V> evictIfNeeded(cache: ConcurrentHashMap<K & Any, V & Any>, maxSize: Int) {
        if (cache.size >= maxSize) {
            cache.keys.toList().take(maxSize / 4).forEach { cache.remove(it) }
        }
    }

    fun isBadSilhouette(bitmap: Bitmap): Boolean {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 8)
        var opaqueCount = 0
        var transparentCount = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = (bitmap[x, y] ushr 24) and 0xFF
                total++
                if (alpha > 200) opaqueCount++
                if (alpha < 30) transparentCount++
                x += step
            }
            y += step
        }
        if (total == 0) return true
        return opaqueCount.toFloat() / total > 0.80f || transparentCount.toFloat() / total > 0.90f
    }

    private fun hasTransparency(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return false

        val corners = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1)
        for ((cx, cy) in corners) {
            val alpha = (bitmap[cx, cy] ushr 24) and 0xFF
            if (alpha < 200) return true
        }

        val step = maxOf(1, minOf(w, h) / 16)
        var transparentCount = 0
        var totalChecked = 0

        for (x in 0 until w step step) {
            val px = x.coerceIn(0, w - 1)
            val a1 = (bitmap[px, 0] ushr 24) and 0xFF
            totalChecked++; if (a1 < 200) transparentCount++
            val a2 = (bitmap[px, h - 1] ushr 24) and 0xFF
            totalChecked++; if (a2 < 200) transparentCount++
        }
        for (y in 0 until h step step) {
            val py = y.coerceIn(0, h - 1)
            val a1 = (bitmap[0, py] ushr 24) and 0xFF
            totalChecked++; if (a1 < 200) transparentCount++
            val a2 = (bitmap[w - 1, py] ushr 24) and 0xFF
            totalChecked++; if (a2 < 200) transparentCount++
        }

        return totalChecked > 0 && transparentCount.toFloat() / totalChecked > 0.10f
    }

    private fun toWhiteAlphaMask(src: Bitmap): Drawable {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        val result = createBitmap(width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        result.density = src.density
        return result.toDrawable(Resources.getSystem())
    }

    private fun toForegroundSilhouette(src: Bitmap): Drawable {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val edgePixels = mutableListOf<Int>()
        val stepX = maxOf(1, w / 10)
        val stepY = maxOf(1, h / 10)
        for (x in 0 until w step stepX) {
            edgePixels.add(pixels[x])
            edgePixels.add(pixels[(h - 1) * w + x])
        }
        for (y in 0 until h step stepY) {
            edgePixels.add(pixels[y * w])
            edgePixels.add(pixels[y * w + w - 1])
        }

        val bgR = edgePixels.map { (it ushr 16) and 0xFF }.sorted().let { it[it.size / 2] }
        val bgG = edgePixels.map { (it ushr 8) and 0xFF }.sorted().let { it[it.size / 2] }
        val bgB = edgePixels.map { it and 0xFF }.sorted().let { it[it.size / 2] }

        var maxDist = 0.0
        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val dist = sqrt(dr * dr + dg * dg + db * db)
            if (dist > maxDist) maxDist = dist
        }

        if (maxDist < 15.0) return toLuminanceAlpha(src)

        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val dr = (r - bgR).toDouble()
            val dg = (g - bgG).toDouble()
            val db = (b - bgB).toDouble()
            val distance = sqrt(dr * dr + dg * dg + db * db)
            val normalized = (distance / maxDist).coerceIn(0.0, 1.0)
            val alpha = (normalized.pow(0.7) * 255.0).toInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val result = createBitmap(w, h)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        result.density = src.density
        return result.toDrawable(Resources.getSystem())
    }

    private fun toLuminanceAlpha(src: Bitmap): Drawable {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var lumSum = 0L
        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lumSum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        val avgLum = if (pixels.isNotEmpty()) lumSum / pixels.size else 128L
        val invert = avgLum > 128

        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            var lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            if (invert) lum = 255 - lum
            val a = (lum * 2).coerceIn(0, 255)
            pixels[i] = (a shl 24) or 0x00FFFFFF
        }

        val result = createBitmap(w, h)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        result.density = src.density
        return result.toDrawable(Resources.getSystem())
    }

    fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap!!
        val w = max(1, intrinsicWidth)
        val h = max(1, intrinsicHeight)
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)
        setBounds(0, 0, w, h)
        draw(canvas)
        return bmp
    }
}
