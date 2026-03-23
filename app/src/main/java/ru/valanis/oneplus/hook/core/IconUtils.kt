package ru.valanis.oneplus.hook.core

import android.app.Notification
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.scale
import de.robv.android.xposed.XposedHelpers
import ru.valanis.oneplus.statusbar.material.R
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

    fun clearAllCaches() {
        iconCache.clear()
        monochromeCache.clear()
        fitCache.clear()
        rawIconCache.clear()
        descriptorCache.clear()
    }

    fun customSmallIcon(pkg: String, notification: Notification? = null): Icon? {
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
