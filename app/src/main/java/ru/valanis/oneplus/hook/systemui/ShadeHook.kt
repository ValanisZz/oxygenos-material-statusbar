package ru.valanis.oneplus.hook.systemui

import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.IconUtils.toBitmap
import ru.valanis.oneplus.hook.core.PreferenceUtils
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("PrivateApi")
object ShadeHook {

    private const val TAG = "OxygenOSMaterialIcons"
    // Diagnostics for icon-swap flicker (bind path + fallback redraws)
    private const val DEBUG_FLICKER = false
    private const val MAX_CACHE = 60
    // View-tag key holding Pair(sourceKey, drawable we set); (key ushr 24) must be >= 2
    private const val ICON_TAG_KEY = 0x7A9C0DE1.toInt()
    // View-tag key: true = this icon view holds our full-color replacement, so the shade's
    // monochrome setOriginalIconColor tint must be suppressed; false = stock icon, leave it.
    private const val OUR_ICON_TAG = 0x7A9C0DE2.toInt()
    private val appIconCache = ConcurrentHashMap<String, Drawable>()

    // A drawable wrapper that ignores color-filter / tint. The shade tints the small icon
    // via setColorFilter directly on the drawable (setOriginalIconColor, updateIconColor) in
    // a pass that runs after we set it; wrapping makes those calls no-ops.
    private class NoTintDrawable(base: Drawable) :
        android.graphics.drawable.DrawableWrapper(base) {
        init {
            // The base may already carry a tint applied before we wrapped it — clear it,
            // then block any further tinting below.
            base.clearColorFilter()
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun setColorFilter(color: Int, mode: android.graphics.PorterDuff.Mode) {}
        override fun setTintList(tint: android.content.res.ColorStateList?) {}
        override fun setTint(tintColor: Int) {}
    }

    // Cache key suffix: the themed-icon palette differs between light and dark, so the
    // two variants must not share an entry.
    private fun themeSuffix(context: android.content.Context): Char =
        if ((context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) 'd' else 'l'

    /**
     * Themed-icon palette, read from the live Material You system colors so it follows the
     * user's wallpaper/accent and the light/dark mode instead of any fixed pair.
     * Returns background to glyph, in the pairing the launcher uses for themed icons.
     */
    private fun themedIconColors(context: android.content.Context): Pair<Int, Int> {
        val res = context.resources
        val theme = try { context.theme } catch (_: Throwable) { null }
        val night = themeSuffix(context) == 'd'
        val bgId = if (night) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100
        val fgId = if (night) android.R.color.system_accent1_100 else android.R.color.system_neutral1_800
        return res.getColor(bgId, theme) to res.getColor(fgId, theme)
    }

    // Below this share of opaque pixels the icon is a bare glyph with no background shape,
    // so there is no background tone to remap — it only gets tinted.
    private const val BACKGROUND_COVERAGE = 0.35f
    // Two-tone remapping only makes sense for a near-flat icon. Anything with real shading
    // (grayscale artwork, photos) is left alone rather than being crushed to two colors.
    private const val MAX_MIDTONE_SHARE = 0.35f

    /**
     * Reproduces what the launcher does to the icon it is given, so the shade and the home
     * screen stay in sync whatever icon theme is selected:
     *
     * - colored icon (standard icons, colored packs, gradient packs) -> shown as-is, untouched;
     * - flat two-tone themed icon -> repainted with the current Material You themed-icon
     *   palette, keeping the authored polarity (a dark-background icon stays dark-background);
     * - bare glyph with no background -> tinted with the themed glyph color;
     * - anything with real shading -> left alone.
     *
     * No color is hardcoded: everything comes from [themedIconColors].
     */
    private fun applyLauncherIconTheme(d: Drawable, context: android.content.Context): Drawable {
        return try {
            val src = d.toBitmap()
            val w = src.width
            val h = src.height
            if (w == 0 || h == 0) return d
            // The launcher shows a colored icon as-is; so must we.
            if (IconUtils.isColoredIcon(src)) return d

            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)

            val lum = IntArray(px.size)
            var lumSum = 0L
            var opaque = 0
            var midtone = 0
            for (i in px.indices) {
                val l = ((px[i] ushr 16 and 0xFF) * 299 + (px[i] ushr 8 and 0xFF) * 587 +
                    (px[i] and 0xFF) * 114) / 1000
                lum[i] = l
                if (((px[i] ushr 24) and 0xFF) < 128) continue
                lumSum += l
                opaque++
                if (l in 70..185) midtone++
            }
            if (opaque == 0) return d
            // Shaded artwork: not a themed icon, repainting it would destroy it.
            if (midtone.toFloat() / opaque > MAX_MIDTONE_SHARE) return d

            val (bg, fg) = themedIconColors(context)
            val fgR = (fg ushr 16) and 0xFF; val fgG = (fg ushr 8) and 0xFF; val fgB = fg and 0xFF
            val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)

            if (opaque.toFloat() / px.size < BACKGROUND_COVERAGE) {
                // Bare glyph: keep the shape, take the themed glyph color.
                for (i in px.indices) {
                    val a = (px[i] ushr 24) and 0xFF
                    px[i] = if (a == 0) 0 else (a shl 24) or (fgR shl 16) or (fgG shl 8) or fgB
                }
            } else {
                // Two-tone icon: map its background tone to the themed background and its
                // glyph tone to the themed glyph. Polarity is taken from the icon itself, so
                // a light-glyph-on-dark-background icon is not inverted.
                val lightBackground = lumSum / opaque > 128
                val bgR = (bg ushr 16) and 0xFF; val bgG = (bg ushr 8) and 0xFF; val bgB = bg and 0xFF
                for (i in px.indices) {
                    val a = (px[i] ushr 24) and 0xFF
                    if (a == 0) { px[i] = 0; continue }
                    val t = if (lightBackground) lum[i] / 255f else 1f - lum[i] / 255f
                    val r = (fgR + (bgR - fgR) * t).toInt().coerceIn(0, 255)
                    val g = (fgG + (bgG - fgG) * t).toInt().coerceIn(0, 255)
                    val b = (fgB + (bgB - fgB) * t).toInt().coerceIn(0, 255)
                    px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            out.setPixels(px, 0, w, 0, 0, w, h)
            out.density = src.density
            android.graphics.drawable.BitmapDrawable(context.resources, out)
        } catch (_: Throwable) {
            d
        }
    }

    fun getCachedAppIcon(pkg: String, context: android.content.Context): Drawable {
        ensureIconThemeReceiver(context)
        val key = "$pkg#${themeSuffix(context)}"
        appIconCache[key]?.let { cached ->
            return cached.constantState?.newDrawable()?.mutate() ?: cached
        }
        val loaded = applyLauncherIconTheme(context.packageManager.getApplicationIcon(pkg), context)
        if (appIconCache.size >= MAX_CACHE) {
            appIconCache.keys.take(MAX_CACHE / 4).forEach { appIconCache.remove(it) }
        }
        appIconCache[key] = loaded
        return loaded
    }

    private val appIconAsIconCache = ConcurrentHashMap<String, Icon>()
    private val appIconBitmapCache = ConcurrentHashMap<String, android.graphics.Bitmap>()

    // Bumped whenever the app icons themselves change (icon theme switch, app update).
    // Part of the per-view source key so already-bound icon views redraw instead of
    // keeping the drawable built from the previous theme.
    private val iconGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val iconThemeReceiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Drops the cached app icons so the next draw re-reads them from PackageManager.
     * Only shade entries of the shared descriptor cache are touched — status bar ("sb_")
     * and AOD icons keep their own caches and must not be disturbed.
     */
    private fun invalidateAppIcons(pkg: String?) {
        if (pkg == null) {
            appIconCache.clear()
            appIconAsIconCache.clear()
            appIconBitmapCache.clear()
            IconUtils.descriptorCache.keys.removeIf { it.startsWith("shade_") }
        } else {
            appIconCache.keys.removeIf { it.substringBefore('#') == pkg }
            appIconAsIconCache.keys.removeIf { it.substringBefore('#') == pkg }
            appIconBitmapCache.keys.removeIf { it.substringBefore('#') == pkg }
            IconUtils.descriptorCache.keys.removeIf { it.startsWith("shade_$pkg") }
        }
        iconGeneration.incrementAndGet()
    }

    /**
     * The icon theme (Settings > Personalization > Icons) is delivered as an RRO overlay and
     * the themed palette follows the wallpaper/accent, so both the drawables we cache and the
     * colors we repaint them with can change under us. Listen for overlay / package changes
     * (per package) and for theme / wallpaper / configuration changes (everything), so the
     * shade keeps following whatever the launcher is showing.
     */
    private fun ensureIconThemeReceiver(context: android.content.Context) {
        if (!iconThemeReceiverRegistered.compareAndSet(false, true)) return
        try {
            val app = context.applicationContext ?: context
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    invalidateAppIcons(intent?.data?.schemeSpecificPart)
                }
            }
            val pkgFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction("android.intent.action.OVERLAY_CHANGED")
                addDataScheme("package")
            }
            // Separate filter: these carry no data URI, so they would never match a filter
            // that declares a data scheme.
            val themeFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(android.content.Intent.ACTION_WALLPAPER_CHANGED)
                addAction("android.intent.action.OVERLAY_CHANGED")
            }
            app.registerReceiver(receiver, pkgFilter, android.content.Context.RECEIVER_EXPORTED)
            app.registerReceiver(receiver, themeFilter, android.content.Context.RECEIVER_EXPORTED)
        } catch (_: Throwable) {}
    }

    // Strips the shade's monochrome tint / dark-theme dim from the icon view so our
    // full-color replacement renders as-is.
    private fun stripIconTint(v: ImageView, pkg: String) {
        v.imageTintList = null
        v.colorFilter = null
        v.imageAlpha = 255
        // The shade tints the small icon by calling setColorFilter directly on the DRAWABLE
        // (setOriginalIconColor / updateIconColor), which ImageView-level clears never touch.
        v.drawable?.clearColorFilter()
    }

    private fun getCachedAppIconAsIcon(pkg: String, context: android.content.Context): Icon? {
        val key = "$pkg#${themeSuffix(context)}"
        appIconAsIconCache[key]?.let { return it }
        return try {
            val d = getCachedAppIcon(pkg, context)
            // Render at a fixed small size: adaptive icons are ~432 px at intrinsic size,
            // which would waste memory across MAX_CACHE packages
            val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(android.graphics.Canvas(bmp))
            val icon = Icon.createWithBitmap(bmp)
            if (appIconAsIconCache.size >= MAX_CACHE) {
                appIconAsIconCache.keys.take(MAX_CACHE / 4).forEach {
                    appIconAsIconCache.remove(it)
                    appIconBitmapCache.remove(it)
                }
            }
            appIconAsIconCache[key] = icon
            appIconBitmapCache[key] = bmp
            icon
        } catch (_: Throwable) {
            null
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val expandableRowClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            lpparam.classLoader
        )

        val getEntryMethod: Method = expandableRowClass.getDeclaredMethod("getEntry")
        val getSbnMethodCache = ConcurrentHashMap<Class<*>, Method>()

        fun setShadeIcon(wrapper: Any, mRow: Any?) {
            // With the shade option off only per-category icons are applied here
            val shadeEnabled = PreferenceUtils.isShadeEnabled()
            if (!shadeEnabled && !PreferenceUtils.isNotificationCategoryIconsEnabled()) return
            try {
                val row = mRow ?: XposedHelpers.getObjectField(wrapper, "mRow") ?: return
                val mIcon = XposedHelpers.getObjectField(wrapper, "mIcon") as? ImageView ?: return
                val entry = getEntryMethod.invoke(row) ?: return
                val getSbn = getSbnMethodCache.getOrPut(entry.javaClass) {
                    entry.javaClass.getDeclaredMethod("getSbn")
                }
                val sbn = getSbn.invoke(entry) ?: return
                val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
                val channelId = try { notification?.channelId } catch (_: Throwable) { null } ?: ""

                val channelPath =
                    if (channelId.isNotEmpty() && PreferenceUtils.isNotificationCategoryIconsEnabled())
                        ChannelIconStore.getShadePath(pkg, channelId)
                    else null
                if (!shadeEnabled && channelPath == null) return
                // Shade option off: the category icon follows the stock look - tinted by the
                // shade like its neighbours when marked monochrome, shown as-is otherwise
                val tinted = !shadeEnabled && ChannelIconStore.isMonochrome(pkg, channelId)
                val sourceKey = if (channelPath != null) {
                    val mtime = try { java.io.File(channelPath).lastModified() } catch (_: Throwable) { 0L }
                    "$channelPath:$mtime"
                } else "app:$pkg#${themeSuffix(mIcon.context)}#${iconGeneration.get()}"

                if (tinted) {
                    mIcon.setTag(OUR_ICON_TAG, false)
                    val prev = mIcon.getTag(ICON_TAG_KEY) as? Pair<*, *>
                    if (prev != null && prev.first == sourceKey && prev.second === mIcon.drawable) return
                    val d = channelPath?.let { IconUtils.loadDrawableFromXmlPath(it, mIcon.context) } ?: return
                    // Carry over the tint the shade already put on the stock drawable; later
                    // tint passes (theme change) reach this plain drawable on their own
                    d.colorFilter = mIcon.drawable?.colorFilter
                    mIcon.setImageDrawable(d)
                    mIcon.setTag(ICON_TAG_KEY, sourceKey to d)
                    return
                }

                // We own this icon. Mark it so the setOriginalIconColor hook suppresses the
                // shade's monochrome tint, and clear any view-level tint/dim as well.
                mIcon.setTag(OUR_ICON_TAG, true)
                stripIconTint(mIcon, pkg)

                // Frequent updates of the same notification: if our NoTint drawable is still
                // in place and the source hasn't changed, don't redraw. (Any later tint pass
                // is a no-op on it anyway, so nothing to re-clean.)
                val prev = mIcon.getTag(ICON_TAG_KEY) as? Pair<*, *>
                if (prev != null && prev.first == sourceKey &&
                    prev.second === mIcon.drawable && mIcon.drawable is NoTintDrawable
                ) return

                val base = channelPath?.let { IconUtils.loadDrawableFromXmlPath(it, mIcon.context) }
                    ?: getCachedAppIcon(pkg, mIcon.context)
                // Wrap so the shade's setColorFilter/setTintList (setOriginalIconColor and
                // updateIconColor, which run at inflation AFTER us) can't wash out the icon.
                val d = NoTintDrawable(base)
                if (DEBUG_FLICKER) android.util.Log.e(
                    TAG,
                    "setShadeIcon SET pkg=$pkg src=$sourceKey prevKey=${prev?.first} drawableMatch=${prev?.second === mIcon.drawable}"
                )
                mIcon.setImageDrawable(d)
                mIcon.setTag(ICON_TAG_KEY, sourceKey to d)
            } catch (_: Throwable) {}
        }

        val pairs = listOf(
            "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp" to "proxyOnContentUpdated",
            "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper" to "onContentUpdated",
            "com.android.systemui.statusbar.notification.NotificationHeaderViewWrapper" to "onContentUpdated",
            "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationGroupTemplateWrapper" to "onContentUpdated"
        )
        for ((name, methodName) in pairs) {
            try {
                val wrapperClass = XposedHelpers.findClass(name, lpparam.classLoader)
                val rowArgIdx = if (methodName == "proxyOnContentUpdated") 0 else -1
                XposedBridge.hookAllMethods(wrapperClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mRow = if (rowArgIdx >= 0) param.args.getOrNull(rowArgIdx) else null
                        setShadeIcon(param.thisObject, mRow)
                    }
                })
            } catch (_: Throwable) {}
        }

        // Replace the small icon at RemoteViews bind time, BEFORE the first draw.
        // The onContentUpdated hooks above fire after the original icon has already been
        // rendered for a frame, which shows as a visible icon swap in the open shade.
        // Async inflation goes through setImageIconAsync (background thread), sync
        // apply through setImageIcon — both must be covered.
        fun resolvePkgFromRow(view: android.view.View): String? {
            var p: android.view.ViewParent? = view.parent
            var depth = 0
            while (p != null && depth < 15) {
                if (expandableRowClass.isInstance(p)) {
                    val entry = getEntryMethod.invoke(p) ?: return null
                    val getSbn = getSbnMethodCache.getOrPut(entry.javaClass) {
                        entry.javaClass.getDeclaredMethod("getSbn")
                    }
                    val sbn = getSbn.invoke(entry) ?: return null
                    return XposedHelpers.callMethod(sbn, "getPackageName") as? String
                }
                p = (p as? android.view.View)?.parent
                depth++
            }
            return null
        }

        val bindTimeIconHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!PreferenceUtils.isShadeEnabled()) return
                try {
                    val view = param.thisObject as? ImageView ?: return
                    if (view.id != android.R.id.icon) return
                    val icon = param.args.getOrNull(0) as? Icon ?: return
                    val pkg: String?
                    if (icon.type == Icon.TYPE_RESOURCE) {
                        pkg = try { icon.resPackage } catch (_: Throwable) { null }
                    } else {
                        // Bitmap icons may already be ours (bind-time replacement or
                        // patched mSmallIcon) — leave those in place
                        if (appIconAsIconCache.containsValue(icon) ||
                            IconUtils.isChannelOverrideIcon(icon)
                        ) return
                        // App ships a bitmap small icon: no resPackage, resolve via the row
                        pkg = resolvePkgFromRow(view)
                    }
                    if (pkg.isNullOrEmpty()) {
                        if (DEBUG_FLICKER) android.util.Log.e(
                            TAG, "bind ${param.method.name}: pkg unresolved, iconType=${icon.type}"
                        )
                        return
                    }
                    // Channel overrides are applied via patched mSmallIcon / onContentUpdated
                    if (ChannelIconStore.hasPackage(pkg)) return
                    val replacement = getCachedAppIconAsIcon(pkg, view.context)
                    if (DEBUG_FLICKER) android.util.Log.e(
                        TAG,
                        "bind ${param.method.name}: pkg=$pkg iconType=${icon.type} replaced=${replacement != null} thread=${Thread.currentThread().name}"
                    )
                    if (replacement == null) { view.setTag(OUR_ICON_TAG, false); return }
                    param.args[0] = replacement
                    // Full-color replacement — mark the view so the setOriginalIconColor hook
                    // suppresses the monochrome tint, and clear any view-level tint/dim.
                    view.setTag(OUR_ICON_TAG, true)
                    view.imageTintList = null
                    view.colorFilter = null
                    view.imageAlpha = 255
                } catch (_: Throwable) {}
            }

            // setImageIcon builds a plain BitmapDrawable that the header still tints via
            // updateIconColor (a pass we don't reliably intercept). Wrap it in NoTintDrawable
            // right after binding so those setColorFilter calls become no-ops.
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.thisObject as? ImageView ?: return
                    if (view.getTag(OUR_ICON_TAG) != true) return
                    val d = view.drawable ?: return
                    if (d is NoTintDrawable) return
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                        view.setImageDrawable(NoTintDrawable(d))
                    } else {
                        view.post {
                            try {
                                val cur = view.drawable
                                if (view.getTag(OUR_ICON_TAG) == true &&
                                    cur != null && cur !is NoTintDrawable
                                ) view.setImageDrawable(NoTintDrawable(cur))
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        for (name in listOf("com.android.internal.widget.CachingIconView", "android.widget.ImageView")) {
            try {
                val cls = XposedHelpers.findClass(name, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "setImageIcon", bindTimeIconHook)
                XposedBridge.hookAllMethods(cls, "setImageIconAsync", bindTimeIconHook)
            } catch (_: Throwable) {}
        }

        // The shade tints the small-icon drawable to a flat monochrome color via
        // CachingIconView.setOriginalIconColor (pale gray on dark theme). Suppress it for
        // views holding our full-color replacement; leave stock icons tinted as normal.
        val suppressIconColorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val v = param.thisObject as? android.view.View ?: return
                    if (v.getTag(OUR_ICON_TAG) == true) {
                        param.result = null
                        (v as? ImageView)?.drawable?.clearColorFilter()
                    }
                } catch (_: Throwable) {}
            }
        }
        for (name in listOf(
            "com.android.internal.widget.CachingIconView",
            "com.oplus.systemui.statusbar.notification.row.NotificationRowIconView",
            "com.android.systemui.statusbar.notification.row.NotificationRowIconView"
        )) {
            try {
                val cls = XposedHelpers.findClass(name, lpparam.classLoader)
                XposedBridge.hookAllMethods(cls, "setOriginalIconColor", suppressIconColorHook)
            } catch (_: Throwable) {}
        }

        // The collapsed-group representative icon is set via setImageDrawable on
        // OplusGroupNotificationRowIconView by the system (a child notification's small icon),
        // AFTER our bind-time replacement — replace it with the app icon so it matches the
        // colored per-app icons shown for single notifications. setImageDrawable is inherited
        // from ImageView (not overridden), so hook the base method and filter by the group view
        // class; hooking the subclass directly matches zero methods and never fires.
        val groupRowIconClass = try {
            XposedHelpers.findClass(
                "com.oplus.systemui.notification.row.oplusgroup.OplusGroupNotificationRowIconView",
                lpparam.classLoader
            )
        } catch (_: Throwable) { null }
        if (groupRowIconClass != null) {
            val groupIconHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val shadeEnabled = PreferenceUtils.isShadeEnabled()
                    if (!shadeEnabled && !PreferenceUtils.isNotificationCategoryIconsEnabled()) return
                    try {
                        val view = param.thisObject ?: return
                        if (!groupRowIconClass.isInstance(view)) return
                        val v = view as? ImageView ?: return
                        val arg = param.args.getOrNull(0) ?: return

                        var entry: Any? = null
                        for (fname in listOf("mEntry", "entry", "mNotificationEntry")) {
                            try {
                                entry = XposedHelpers.getObjectField(v, fname)
                                if (entry != null) break
                            } catch (_: Throwable) {}
                        }
                        if (entry == null) {
                            var p: android.view.ViewParent? = v.parent
                            var depth = 0
                            while (p != null && depth < 15) {
                                if (expandableRowClass.isInstance(p)) {
                                    entry = getEntryMethod.invoke(p)
                                    break
                                }
                                p = (p as? android.view.View)?.parent
                                depth++
                            }
                        }
                        val e = entry ?: return
                        val getSbn = getSbnMethodCache.getOrPut(e.javaClass) {
                            e.javaClass.getDeclaredMethod("getSbn")
                        }
                        val sbn = getSbn.invoke(e) ?: return
                        val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                        val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification
                        val channelId = try { notification?.channelId } catch (_: Throwable) { null } ?: ""
                        val ctx = v.context

                        // Shade option off: only per-category icons are applied
                        if (!shadeEnabled && (channelId.isEmpty() ||
                                ChannelIconStore.getShadePath(pkg, channelId) == null)
                        ) return
                        if (ChannelIconStore.isMonochrome(pkg, channelId)) return

                        if (arg is NoTintDrawable) return
                        val base: android.graphics.drawable.Drawable =
                            if (channelId.isNotEmpty() && ChannelIconStore.getShadePath(pkg, channelId) != null) {
                                IconUtils.channelOverrideIcon(pkg, channelId, ctx, "shade")?.loadDrawable(ctx)
                                    ?: getCachedAppIcon(pkg, ctx)
                            } else {
                                getCachedAppIcon(pkg, ctx)
                            }
                        // Wrap so the shade's theme tint can't wash out the app icon, same as
                        // single notifications.
                        param.args[0] = NoTintDrawable(base)
                    } catch (_: Throwable) {}
                }
            }
            for (name in listOf("com.android.internal.widget.CachingIconView", "android.widget.ImageView")) {
                try {
                    val cls = XposedHelpers.findClass(name, lpparam.classLoader)
                    XposedBridge.hookAllMethods(cls, "setImageDrawable", groupIconHook)
                } catch (_: Throwable) {}
            }
        }
    }
}
