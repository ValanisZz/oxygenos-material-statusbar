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

    private val SYSTEM_DIALER_PKGS = setOf(
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.oplus.dialer"
    )

    // Package -> author icon for all of its notifications
    private val CUSTOM_SMALL_ICONS = mapOf(
        "com.android.mms" to R.drawable.ic_notify_mms,
        "com.oplus.battery" to R.drawable.ic_notify_battery_full,
        "com.oplus.phonemanager" to R.drawable.ic_notify_phonemanager,
        "com.oneplus.deskclock" to R.drawable.ic_notify_deskclock,
        "com.android.providers.downloads" to R.drawable.ic_notify_download,
        "com.android.providers.donwloads" to R.drawable.ic_notify_download,
        "com.oplus.ota" to R.drawable.ic_notify_ota,
    )

    /** Every package that can get an author icon, in settings-screen order. */
    val CUSTOM_ICON_PKGS: List<String> =
        CUSTOM_SMALL_ICONS.keys.toList() + "com.android.systemui" + SYSTEM_DIALER_PKGS

    val iconCache = ConcurrentHashMap<String, Icon>()
    val monochromeCache = ConcurrentHashMap<String, Drawable>()
    private val fitCache = ConcurrentHashMap<String, Drawable>()
    private val rawIconCache = ConcurrentHashMap<String, Drawable>()
    val descriptorCache = ConcurrentHashMap<String, Icon>()

    private val channelDrawableByPath = ConcurrentHashMap<String, Drawable>()
    private val channelIconByKey = ConcurrentHashMap<String, Icon>()

    // Bumped when the prefs or the channel map change: work memoized per notification
    // (small-icon patch, AOD icon) is redone on the next touch
    val configGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    class IconOwner(val pkg: String, val channelId: String)

    // Identity-keyed (Icon / Bitmap don't override equals), weak: entries die with the icon
    private val iconOwners = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Icon, IconOwner>())
    private val channelOverrideIcons = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Icon, Boolean>())
    private val bitmapHashes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Bitmap, Int>())

    /** Remembers which notification a small icon belongs to, for the bind-time shade hook. */
    fun setIconOwner(icon: Icon, pkg: String, channelId: String) {
        iconOwners[icon] = IconOwner(pkg, channelId)
    }

    fun iconOwner(icon: Icon): IconOwner? = iconOwners[icon]

    /** A private copy of a channel-override icon (same bitmap), so it can have its own owner. */
    fun copyChannelOverrideIcon(icon: Icon): Icon {
        val bmp = try { XposedHelpers.callMethod(icon, "getBitmap") as? Bitmap } catch (_: Throwable) { null }
            ?: return icon
        return Icon.createWithBitmap(bmp).also { channelOverrideIcons[it] = true }
    }

    fun pathKey(path: String): String =
        // Provider files are named by content: a changed icon is a new URI, nothing to stat
        if (path.startsWith("content://")) path
        else "$path:${try { File(path).lastModified() } catch (_: Throwable) { 0L }}"

    /** The channel map changed: drop everything derived from it. */
    fun onChannelMapChanged() {
        channelIconByKey.clear()
        loadFailureAtMs.clear()
        descriptorCache.keys.removeIf { it.startsWith("shade_") }
        configGeneration.incrementAndGet()
    }

    /** [pkg] was updated/changed: its resource ids may now point at other drawables. */
    fun invalidatePackage(pkg: String) {
        val marker = "_${pkg}_"
        monochromeCache.keys.removeIf { it.contains(marker) }
        fitCache.keys.removeIf { it.contains(marker) }
        rawIconCache.keys.removeIf { it.contains(marker) }
        descriptorCache.keys.removeIf { it.contains(marker) }
        configGeneration.incrementAndGet()
    }

    // Paths that recently failed to load — don't retry for 30 s
    private val loadFailureAtMs = ConcurrentHashMap<String, Long>()
    private val preloading = java.util.concurrent.atomic.AtomicBoolean(false)

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
        channelIconByKey.clear()
        loadFailureAtMs.clear()
        ChannelIconStore.invalidateCache()
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Reads an icon: a module provider URI ([ChannelIconStore]) or a plain readable file. */
    private fun readFileBytes(path: String, context: Context): ByteArray? {
        return try {
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { it.readBytes() }
            } else {
                File(path).readBytes()
            }
        } catch (t: Throwable) {
            android.util.Log.e("OxygenOSMaterialIcons", "readFileBytes: $path: ${t.message}")
            null
        }
    }

    /**
     * Loads a drawable from an on-disk file (user-provided path).
     * Supports VectorDrawable XML and bitmap formats (PNG, WebP, JPG).
     */
    fun loadDrawableFromXmlPath(path: String, context: Context): Drawable? {
        return try {
            val cacheKey = pathKey(path)
            channelDrawableByPath[cacheKey]?.let { d ->
                return d.constantState?.newDrawable()?.mutate() ?: d
            }
            loadFailureAtMs[path]?.let { failedAt ->
                if (System.currentTimeMillis() - failedAt < 30_000L) return null
            }
            val bytes = readFileBytes(path, context)
            if (bytes == null) {
                recordLoadFailure(path)
                return null
            }
            val drawable = when {
                path.endsWith(".xml", ignoreCase = true) ->
                    parseVectorDrawableXml(String(bytes, Charsets.UTF_8), context)
                path.endsWith(".svg", ignoreCase = true) ->
                    parseSvgXml(String(bytes, Charsets.UTF_8), context)
                else -> {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.let { BitmapDrawable(context.resources, it) }
                }
            }
            if (drawable == null) {
                recordLoadFailure(path)
                return null
            }
            loadFailureAtMs.remove(path)
            val mutable = drawable.mutate()
            evictIfNeeded(channelDrawableByPath, 40)
            channelDrawableByPath[cacheKey] = mutable
            mutable.constantState?.newDrawable()?.mutate() ?: mutable
        } catch (_: Throwable) {
            recordLoadFailure(path)
            null
        }
    }

    private fun recordLoadFailure(path: String) {
        evictIfNeeded(loadFailureAtMs, 40)
        loadFailureAtMs[path] = System.currentTimeMillis()
    }

    /**
     * Warms the drawable cache off the UI thread so the first draw of a freshly posted
     * notification is a cache hit instead of a synchronous file read + parse.
     */
    fun preloadChannelIcons(values: Collection<String>) {
        val paths = values.filter { it.startsWith("/") || it.startsWith("content://") }.distinct()
        if (paths.isEmpty()) return
        if (!preloading.compareAndSet(false, true)) return
        Thread {
            try {
                val ctx = applicationContextOrNull() ?: return@Thread
                for (p in paths) {
                    try { loadDrawableFromXmlPath(p, ctx) } catch (_: Throwable) {}
                }
            } finally {
                preloading.set(false)
            }
        }.apply {
            name = "OxygenMaterialIconPreload"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
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

    private class SvgStyle(
        val fill: Int?,
        val stroke: Int?,
        val strokeWidth: Float,
        val evenOdd: Boolean,
        val cap: Paint.Cap,
        val join: Paint.Join,
        val matrix: android.graphics.Matrix
    )

    // Elements whose content is not drawn directly
    private val SVG_NON_RENDERED = setOf(
        "defs", "clippath", "mask", "symbol", "marker", "pattern", "lineargradient",
        "radialgradient", "filter", "title", "desc", "metadata", "style"
    )

    /**
     * Minimal SVG renderer: path / line / rect / circle / ellipse / polygon / polyline with
     * fill, stroke, stroke-width, fill-rule, linecap and linejoin inherited from <svg> / <g>
     * (attributes or style="..."), plus transforms. SVG path data (`d`) uses the same syntax as
     * VectorDrawable `pathData`, so PathParser handles it.
     */
    private fun parseSvgXml(xmlContent: String, context: Context): Drawable? {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xmlContent))

        var viewBox: FloatArray? = null
        val shapes = mutableListOf<Triple<Path, Paint?, Paint?>>()
        val stack = ArrayDeque<SvgStyle>()
        stack.addLast(SvgStyle(Color.BLACK, null, 1f, false, Paint.Cap.BUTT, Paint.Join.MITER, android.graphics.Matrix()))
        // Depth of the hidden element being skipped, -1 = none
        var skipDepth = -1

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (skipDepth >= 0) continue
                    val tag = parser.name.lowercase()
                    val inline = parseSvgStyleAttr(parser.getAttributeValue(null, "style") ?: "")
                    fun attr(name: String): String? = inline[name] ?: parser.getAttributeValue(null, name)?.trim()
                    if (tag in SVG_NON_RENDERED || attr("display") == "none") {
                        skipDepth = parser.depth
                        continue
                    }
                    if (tag == "svg" && viewBox == null) viewBox = parseSvgViewBox(parser)
                    val parent = stack.last()
                    val matrix = android.graphics.Matrix(parent.matrix)
                    attr("transform")?.let { applySvgTransform(matrix, it) }
                    val st = SvgStyle(
                        fill = svgPaint(attr("fill"), parent.fill),
                        stroke = svgPaint(attr("stroke"), parent.stroke),
                        strokeWidth = attr("stroke-width")?.let { svgNumber(it) } ?: parent.strokeWidth,
                        evenOdd = attr("fill-rule")?.let { it == "evenodd" } ?: parent.evenOdd,
                        cap = when (attr("stroke-linecap")) {
                            "round" -> Paint.Cap.ROUND
                            "square" -> Paint.Cap.SQUARE
                            "butt" -> Paint.Cap.BUTT
                            else -> parent.cap
                        },
                        join = when (attr("stroke-linejoin")) {
                            "round" -> Paint.Join.ROUND
                            "bevel" -> Paint.Join.BEVEL
                            "miter" -> Paint.Join.MITER
                            else -> parent.join
                        },
                        matrix = matrix
                    )
                    // Every element is pushed and popped on its END_TAG (self-closing ones too)
                    stack.addLast(st)
                    val path = svgShapePath(tag, parser) ?: continue
                    path.fillType = if (st.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    path.transform(st.matrix)
                    // The stroke scales with the element's transform
                    val v = FloatArray(9).also { st.matrix.getValues(it) }
                    val scale = sqrt(kotlin.math.abs(v[0] * v[4] - v[1] * v[3]))
                    val fillPaint = st.fill?.takeIf { tag != "line" }?.let { c ->
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = c }
                    }
                    val strokePaint = st.stroke?.takeIf { st.strokeWidth > 0f }?.let { c ->
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            color = c
                            strokeWidth = st.strokeWidth * scale
                            strokeCap = st.cap
                            strokeJoin = st.join
                        }
                    }
                    if (fillPaint != null || strokePaint != null) shapes.add(Triple(path, fillPaint, strokePaint))
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth >= 0) {
                        // The skipped element itself was never pushed
                        if (parser.depth == skipDepth) skipDepth = -1
                        continue
                    }
                    if (stack.size > 1) stack.removeLast()
                }
            }
        }

        if (shapes.isEmpty()) {
            de.robv.android.xposed.XposedBridge.log("OxygenOSMaterialIcons parseSvgXml: no renderable shapes")
            return null
        }

        val vb = viewBox ?: floatArrayOf(0f, 0f, 24f, 24f)
        val density = context.resources.displayMetrics.density
        val size = (24 * density).toInt().coerceAtLeast(48)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.scale(size / vb[2], size / vb[3])
        // Material Symbols use a shifted viewBox ("0 -960 960 960")
        canvas.translate(-vb[0], -vb[1])
        for ((path, fill, stroke) in shapes) {
            fill?.let { canvas.drawPath(path, it) }
            stroke?.let { canvas.drawPath(path, it) }
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun parseSvgViewBox(parser: XmlPullParser): FloatArray {
        parser.getAttributeValue(null, "viewBox")?.trim()?.split(Regex("[,\\s]+"))
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }
            ?.let { return it.toFloatArray() }
        val w = parser.getAttributeValue(null, "width")?.let { svgNumber(it) }?.takeIf { it > 0f } ?: 24f
        val h = parser.getAttributeValue(null, "height")?.let { svgNumber(it) }?.takeIf { it > 0f } ?: 24f
        return floatArrayOf(0f, 0f, w, h)
    }

    private fun svgShapePath(tag: String, parser: XmlPullParser): Path? {
        fun num(name: String): Float? = parser.getAttributeValue(null, name)?.let { svgNumber(it) }
        fun n(name: String): Float = num(name) ?: 0f
        return when (tag) {
            "path" -> parser.getAttributeValue(null, "d")?.let { createPathFromData(it) }
            "line" -> Path().apply { moveTo(n("x1"), n("y1")); lineTo(n("x2"), n("y2")) }
            "rect" -> {
                val x = n("x"); val y = n("y"); val w = n("width"); val h = n("height")
                if (w <= 0f || h <= 0f) return null
                val rx = (num("rx") ?: num("ry") ?: 0f).coerceAtMost(w / 2)
                val ry = (num("ry") ?: num("rx") ?: 0f).coerceAtMost(h / 2)
                Path().apply { addRoundRect(android.graphics.RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW) }
            }
            "circle" -> n("r").takeIf { it > 0f }?.let { r ->
                Path().apply { addCircle(n("cx"), n("cy"), r, Path.Direction.CW) }
            }
            "ellipse" -> {
                val rx = n("rx"); val ry = n("ry")
                if (rx <= 0f || ry <= 0f) return null
                val cx = n("cx"); val cy = n("cy")
                Path().apply { addOval(android.graphics.RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW) }
            }
            "polygon", "polyline" -> {
                val pts = parser.getAttributeValue(null, "points")?.trim()?.split(Regex("[,\\s]+"))
                    ?.mapNotNull { it.toFloatOrNull() } ?: return null
                if (pts.size < 4) return null
                Path().apply {
                    moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
                    if (tag == "polygon") close()
                }
            }
            else -> null
        }
    }

    private fun applySvgTransform(matrix: android.graphics.Matrix, transform: String) {
        for (m in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(transform)) {
            val a = m.groupValues[2].trim().split(Regex("[,\\s]+")).mapNotNull { it.toFloatOrNull() }
            val t = android.graphics.Matrix()
            when (m.groupValues[1]) {
                "translate" -> t.setTranslate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "scale" -> { val sx = a.getOrElse(0) { 1f }; t.setScale(sx, a.getOrElse(1) { sx }) }
                "rotate" -> if (a.size >= 3) t.setRotate(a[0], a[1], a[2]) else t.setRotate(a.getOrElse(0) { 0f })
                "skewX" -> t.setSkew(kotlin.math.tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                "skewY" -> t.setSkew(0f, kotlin.math.tan(Math.toRadians(a.getOrElse(0) { 0f }.toDouble())).toFloat())
                "matrix" -> if (a.size == 6) t.setValues(floatArrayOf(a[0], a[2], a[4], a[1], a[3], a[5], 0f, 0f, 1f))
            }
            matrix.preConcat(t)
        }
    }

    private fun svgNumber(s: String): Float? =
        Regex("^[-+]?(\\d*\\.\\d+|\\d+\\.?)([eE][-+]?\\d+)?").find(s.trim())?.value?.toFloatOrNull()

    /** null value = inherit, "none" = no paint. */
    private fun svgPaint(value: String?, inherited: Int?): Int? = when (value) {
        null, "inherit" -> inherited
        "none", "transparent" -> null
        else -> parseSvgColor(value)
    }

    private fun parseSvgStyleAttr(style: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (decl in style.split(';')) {
            val parts = decl.trim().split(':', limit = 2)
            if (parts.size == 2) map[parts[0].trim().lowercase()] = parts[1].trim()
        }
        return map
    }

    // Notification icons only use alpha: anything unparseable (currentColor, url(#gradient),
    // rgb(...)) is drawn opaque rather than dropped
    private fun parseSvgColor(colorStr: String): Int {
        val c = colorStr.trim()
        if (Regex("#[0-9a-fA-F]{3}").matches(c)) {
            return Color.parseColor("#" + c.drop(1).map { "$it$it" }.joinToString(""))
        }
        return try { Color.parseColor(c) } catch (_: Throwable) { Color.BLACK }
    }

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
            else    -> ChannelIconStore.getPath(pkg, channelId)
        } ?: return null
        val iconKey = pathKey(path)
        channelIconByKey[iconKey]?.let { return it }
        val d = loadDrawableFromXmlPath(path, context)
        if (d == null) {
            android.util.Log.e("OxygenOSMaterialIcons", "channelOverrideIcon[$pkg/$channelId/$variant]: drawable=null path=$path")
            return null
        }
        val icon = Icon.createWithBitmap(d.toBitmap())
        evictIfNeeded(channelIconByKey, 40)
        channelIconByKey[iconKey] = icon
        channelOverrideIcons[icon] = true
        return icon
    }

    /** True if [icon] is one of the channel-override bitmaps produced by this module. */
    fun isChannelOverrideIcon(icon: Icon): Boolean = channelOverrideIcons.containsKey(icon)

    fun customSmallIcon(pkg: String, notification: Notification? = null, context: Context? = null): Icon? {
        if (!PreferenceUtils.isCustomIconsEnabled()) return null
        val channelId = try { notification?.channelId } catch (_: Throwable) { null } ?: ""
        val category = try { notification?.category } catch (_: Throwable) { null }
        val res = customIconRes(pkg, channelId, category) ?: return null
        if (!PreferenceUtils.isCustomIconEnabled(pkg, channelId)) return null
        return Icon.createWithResource(PreferenceUtils.MODULE_PKG, res)
    }

    /**
     * Author icon for a notification of [pkg] in [channelId], ignoring the user's switches.
     * The settings screen has no notification at hand and passes a category guessed from the channel.
     */
    fun customIconRes(pkg: String, channelId: String, category: String?): Int? {
        CUSTOM_SMALL_ICONS[pkg]?.let { return it }

        if (pkg == "com.android.systemui") {
            val isBattery = channelId.contains("battery", ignoreCase = true) ||
                channelId.contains("bat", ignoreCase = true) ||
                channelId.contains("CRG", ignoreCase = true) ||
                channelId.contains("charge", ignoreCase = true) ||
                channelId.contains("power", ignoreCase = true) ||
                channelId.contains("low", ignoreCase = true) ||
                (category != null && category.contains("battery", ignoreCase = true))
            if (isBattery) return R.drawable.ic_notify_battery_full
        }

        // Missed call: system telephony packages only — third-party apps (e.g. Teams)
        // also use CATEGORY_MISSED_CALL and must keep their own icons
        if (pkg in SYSTEM_DIALER_PKGS && category == Notification.CATEGORY_MISSED_CALL) {
            return R.drawable.ic_notify_missed_call
        }
        return null
    }

    fun getIconCacheKey(icon: Icon, pkg: String): String? {
        return try {
            when (icon.type) {
                // Bitmap / adaptive-bitmap icons have no resource id - key them by pixel content,
                // otherwise all bitmap icons of a package (e.g. Teams missed call vs chat) share one entry
                Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> {
                    val bmp = XposedHelpers.callMethod(icon, "getBitmap") as? Bitmap ?: return null
                    "fallback_${pkg}_${bitmapHash(bmp)}"
                }
                Icon.TYPE_RESOURCE -> {
                    val resId = icon.resId
                    if (resId == 0) null else "r_${icon.resPackage.ifEmpty { pkg }}_$resId"
                }
                // URI icons: keyed by the URI, not by package/channel (6 = TYPE_URI_ADAPTIVE_BITMAP)
                Icon.TYPE_URI, 6 -> "u_${pkg}_${icon.uri}"
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    fun getCachedMonochrome(icon: Icon?, pkg: String, drawable: Drawable, rawPath: Boolean = false): Drawable {
        val baseKey = icon?.let { getIconCacheKey(it, pkg) }
            ?: "fallback_${pkg}_${try { bitmapHash(drawable.toBitmap()) } catch (_: Throwable) { 0 }}"
        val key = if (rawPath) "${baseKey}_raw" else baseKey
        evictIfNeeded(monochromeCache, MAX_MONOCHROME_CACHE)
        val cached = monochromeCache.getOrPut(key) { ensureMonochrome(drawable) }
        return cached.constantState?.newDrawable()?.mutate() ?: cached
    }

    private fun bitmapHash(bmp: Bitmap): Int {
        bitmapHashes[bmp]?.let { return it }
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val hash = 31 * (31 * bmp.width + bmp.height) + pixels.contentHashCode()
        bitmapHashes[bmp] = hash
        return hash
    }

    fun getCachedMonochromeFallback(pkg: String, drawable: Drawable): Drawable {
        return getCachedMonochrome(null, pkg, drawable)
    }

    fun loadIconWithoutTheme(icon: Icon, pkg: String, context: Context): Drawable? {
        try {
            if (icon.type != Icon.TYPE_RESOURCE) return null
            val resId = icon.resId
            if (resId == 0) return null
            val resPkg = icon.resPackage.ifEmpty { pkg }

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
