package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.PreferenceUtils
import ru.valanis.oneplus.statusbar.material.R
import ru.valanis.oneplus.statusbar.material.settings.ModulePreferences
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.max

@SuppressLint("PrivateApi")
object SettingsScreenBuilder {

    private const val TAG = "OxygenOSMaterialIcons"
    private const val MODULE_SETTINGS_MARKER = "oxygen_material_module_settings_screen"
    private const val CUSTOM_ICONS_MARKER = "oxygen_material_custom_icons_screen"
    private const val CUSTOM_ICONS_APP_MARKER = "oxygen_material_custom_icons_app"
    private const val PREVIEW_TAG = "oxygen_material_custom_icon_preview"
    private const val PREVIEW_GAP_DP = 12
    // The theme text color has no disabled state here, so a disabled row's preview is dimmed by alpha
    private const val PREVIEW_DISABLED_ALPHA = 0.3f

    /** Key prefix of the app rows on the custom icons screen (full-width dividers). */
    const val CUSTOM_ICONS_ENTRY_KEY_PREFIX = "oxygen_material_custom_icons_entry_"

    // Pref key -> preview drawable for the custom icons screen. COUI switch rows don't
    // render setIcon, so the preview is added to the row at bind time instead.
    private val customIconPreviews = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    /** Called for every bound preference row: rows are recycled, so stale previews are removed. */
    fun bindCustomIconPreview(key: String?, itemView: View, enabled: Boolean) {
        val existing = itemView.findViewWithTag<android.widget.ImageView>(PREVIEW_TAG)
        val drawable = key?.let { customIconPreviews[it] }
        if (drawable == null) {
            existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return
        }
        val widgetFrame = itemView.findViewById<ViewGroup>(android.R.id.widget_frame) ?: return
        val density = itemView.resources.displayMetrics.density
        if (existing != null) {
            existing.setImageDrawable(drawable)
            existing.alpha = if (enabled) 1f else PREVIEW_DISABLED_ALPHA
            alignPreviewToSwitch(existing, widgetFrame, density)
            return
        }
        val iv = android.widget.ImageView(itemView.context).apply {
            tag = PREVIEW_TAG
            setImageDrawable(drawable)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            alpha = if (enabled) 1f else PREVIEW_DISABLED_ALPHA
        }
        val size = (24 * density).toInt()
        val lp = android.widget.LinearLayout.LayoutParams(size, size).apply {
            marginEnd = (PREVIEW_GAP_DP * density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Inside the widget frame, right before the switch: placed outside it, the frame's own
        // start padding pushes the preview away. The COUI frame is a vertical column - make it a row.
        if (widgetFrame is android.widget.LinearLayout) {
            widgetFrame.orientation = android.widget.LinearLayout.HORIZONTAL
            widgetFrame.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            widgetFrame.addView(iv, 0, lp)
        } else {
            val row = widgetFrame.parent as? android.widget.LinearLayout ?: return
            row.addView(iv, row.indexOfChild(widgetFrame), lp)
        }
        alignPreviewToSwitch(iv, widgetFrame, density)
    }

    // The switch view is wider than its visible track (SwitchCompat draws the track at its end),
    // so a plain margin leaves a much larger gap. After layout, pull the preview over the empty
    // start of the switch so the visible gap is PREVIEW_GAP_DP.
    private fun alignPreviewToSwitch(iv: View, widgetFrame: ViewGroup, density: Float) {
        widgetFrame.post {
            val sw = (0 until widgetFrame.childCount).map { widgetFrame.getChildAt(it) }
                .firstOrNull { it !== iv && it.visibility == View.VISIBLE } ?: return@post
            val trackWidth = try { XposedHelpers.getIntField(sw, "mSwitchWidth") } catch (_: Throwable) { 0 }
            val emptyStart = if (trackWidth in 1..sw.width) sw.width - sw.paddingEnd - trackWidth else sw.paddingStart
            val swMarginStart = (sw.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
            val lp = iv.layoutParams as? ViewGroup.MarginLayoutParams ?: return@post
            val marginEnd = (PREVIEW_GAP_DP * density).toInt() - emptyStart - swMarginStart
            if (lp.marginEnd != marginEnd) {
                android.util.Log.i(TAG, "preview align: ${sw.javaClass.name} w=${sw.width} track=$trackWidth " +
                    "padS=${sw.paddingStart} padE=${sw.paddingEnd} marginS=$swMarginStart -> marginEnd=$marginEnd")
                lp.marginEnd = marginEnd
                iv.layoutParams = lp
            }
        }
    }

    private const val MODULE_PKG = PreferenceUtils.MODULE_PKG
    private const val PREF_FILENAME = "${ModulePreferences.PREF_FILE}.xml"

    fun tryBuild(fragment: Any): Boolean {
        // Sub-screens are checked first: their titles would also match the main screen's title fallback
        val customIconsApp = customIconsAppPkg(fragment)
        val customIconsScreen = customIconsApp == null && isCustomIconsScreen(fragment)
        if (customIconsApp == null && !customIconsScreen && !isInjectedSettingsScreen(fragment)) return false
        val context = try { XposedHelpers.callMethod(fragment, "getContext") as? Context } catch (_: Throwable) { null } ?: return false
        val classLoader = fragment.javaClass.classLoader ?: return false
        val prefManager = try { XposedHelpers.callMethod(fragment, "getPreferenceManager") } catch (_: Throwable) { null } ?: run {
            XposedBridge.log("$TAG injectedSettings: no PreferenceManager for ${fragment.javaClass.name}")
            return false
        }
        val screen = try { XposedHelpers.callMethod(prefManager, "createPreferenceScreen", context) } catch (_: Throwable) { null } ?: run {
            XposedBridge.log("$TAG injectedSettings: createPreferenceScreen failed for ${fragment.javaClass.name}")
            return false
        }

        val settingsCtx = try { context.createPackageContext(PreferenceUtils.MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY) } catch (_: Throwable) { context }
        val widgetLayoutRes = context.resources.getIdentifier("coui_preference_widget_switch_compat", "layout", "com.android.settings")

        val savedPrefs = readPrefs(context.contentResolver)
        // Defaults plus everything saved: the prefs file is rewritten whole on each change
        val currentPrefs = (DEFAULTS + savedPrefs).toMutableMap()

        // Per-app / per-category switches: their keys are dynamic, so readPrefs (fixed keys) doesn't
        // return them - read Settings.Global directly. The prefs file is written via su only, which
        // Settings may not have.
        fun customIconSwitch(key: String): Boolean {
            val v = try { Settings.Global.getInt(context.contentResolver, PreferenceUtils.globalKey(key), -1) } catch (_: Throwable) { -1 }
            return if (v != -1) v == 1 else currentPrefs[key] ?: ModulePreferences.DEFAULT_ENABLE_CUSTOM_ICON
        }

        val enableModule = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_MODULE)
        val enableStatusBar = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_STATUS_BAR)
        val enableShade = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_SHADE)
        val enableAod = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_AOD)
        val enableCategoryIcons = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS)
        val enableCustomIcons = currentPrefs.getValue(ModulePreferences.KEY_ENABLE_CUSTOM_ICONS)

        val prefClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
        val switchCompatClass = XposedHelpers.findClass("androidx.preference.SwitchPreferenceCompat", classLoader)
        val baseCategoryClass = XposedHelpers.findClass("androidx.preference.PreferenceCategory", classLoader)
        val oplusCategoryClass: Class<*>? = try {
            XposedHelpers.findClass("com.oplus.settings.widget.preference.SettingsPreferenceCategory", classLoader)
        } catch (_: Throwable) { null }
        val categoryClass = oplusCategoryClass ?: baseCategoryClass
        val bottomSpacerLayoutRes = context.resources.getIdentifier("module_settings_bottom_spacer", "layout", PreferenceUtils.MODULE_PKG)
        val categoryLayoutRes = context.resources.getIdentifier("oplus_settings_preference_category_layout", "layout", "com.android.settings")
        val switchRowLayoutRes = context.resources.getIdentifier("switch_preference_simple_layout_no_icon", "layout", "com.android.settings")

        val marginTypeZero: Int = if (oplusCategoryClass != null) {
            try {
                val couiCatClass = XposedHelpers.findClass("com.coui.appcompat.preference.COUIPreferenceCategory", classLoader)
                XposedHelpers.getStaticIntField(couiCatClass, "MARGIN_TYPE_ZERO")
            } catch (_: Throwable) { 0 }
        } else 0

        fun createCategory(isFirst: Boolean = false): Any {
            val cat = XposedHelpers.newInstance(categoryClass, context)
            if (categoryLayoutRes != 0 && oplusCategoryClass == null) {
                try { XposedHelpers.callMethod(cat, "setLayoutResource", categoryLayoutRes) } catch (_: Throwable) {}
            }
            if (isFirst && oplusCategoryClass != null) {
                try { XposedHelpers.setObjectField(cat, "mIsSimpleFirstCategory", true) } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(cat, "mMarginTopType", marginTypeZero) } catch (_: Throwable) {}
            }
            return cat
        }

        fun newSwitchPreference(): Any {
            val candidates = listOf(
                "com.coui.appcompat.preference.COUISwitchPreferenceCompat",
                "com.coui.appcompat.preference.COUISwitchPreference"
            )
            for (name in candidates) {
                try {
                    val cls = XposedHelpers.findClass(name, classLoader)
                    return XposedHelpers.newInstance(cls, context)
                } catch (_: Throwable) {}
            }
            return XposedHelpers.newInstance(switchCompatClass, context)
        }

        fun newJumpPreference(): Any = try {
            XposedHelpers.newInstance(XposedHelpers.findClass("com.coui.appcompat.preference.COUIJumpPreference", classLoader), context)
        } catch (_: Throwable) { XposedHelpers.newInstance(prefClass, context) }

        fun addSwitch(
            targetCategory: Any,
            key: String,
            title: CharSequence,
            summaryRes: Int?,
            checked: Boolean,
            enabled: Boolean
        ): Any {
            val sw = newSwitchPreference()
            XposedHelpers.callMethod(sw, "setKey", key)
            XposedHelpers.callMethod(sw, "setTitle", title)
            if (summaryRes != null) {
                XposedHelpers.callMethod(sw, "setSummary", settingsCtx.getString(summaryRes))
            } else {
                try { XposedHelpers.callMethod(sw, "setSummary", "") } catch (_: Throwable) {}
            }
            XposedHelpers.callMethod(sw, "setChecked", checked)
            XposedHelpers.callMethod(sw, "setEnabled", enabled)
            val isAndroidxSwitch = sw.javaClass.name == "androidx.preference.SwitchPreferenceCompat"
            if (switchRowLayoutRes != 0 && isAndroidxSwitch) {
                try { XposedHelpers.callMethod(sw, "setLayoutResource", switchRowLayoutRes) } catch (_: Throwable) {}
            }
            if (widgetLayoutRes != 0 && isAndroidxSwitch) {
                try { XposedHelpers.callMethod(sw, "setWidgetLayoutResource", widgetLayoutRes) } catch (_: Throwable) {}
            }
            XposedHelpers.callMethod(targetCategory, "addPreference", sw)
            return sw
        }

        fun addSpacer(layoutRes: Int) {
            if (layoutRes == 0) return
            val spacer = XposedHelpers.newInstance(prefClass, context)
            try { XposedHelpers.callMethod(spacer, "setTitle", " ") } catch (_: Throwable) {}
            XposedHelpers.callMethod(spacer, "setSelectable", false)
            XposedHelpers.callMethod(spacer, "setEnabled", true)
            XposedHelpers.callMethod(spacer, "setLayoutResource", layoutRes)
            XposedHelpers.callMethod(screen, "addPreference", spacer)
        }

        fun applyUnifiedSectionStyle(preferences: List<Any>) {
            if (preferences.isEmpty()) return
            val lastIndex = preferences.lastIndex
            for (i in preferences.indices) {
                val pref = preferences[i]
                val category = when {
                    preferences.size == 1 -> 0
                    i == 0 -> 1
                    i == lastIndex -> 3
                    else -> 2
                }
                try { XposedHelpers.callMethod(pref, "setLayoutCategory", category) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(pref, "setNeedChangeDrawType", true) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(pref, "setShowDivider", i != lastIndex) } catch (_: Throwable) {}
            }
        }

        fun persist(key: String, value: Boolean) {
            currentPrefs[key] = value
            writePrefs(context.contentResolver, currentPrefs)
        }

        val listenerClass = XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", classLoader)
        val clickListenerClass = XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", classLoader)

        fun finish(): Boolean {
            try { XposedHelpers.callMethod(fragment, "setPreferenceScreen", screen) } catch (_: Throwable) {
                XposedBridge.log("$TAG injectedSettings: setPreferenceScreen failed for ${fragment.javaClass.name}")
                return false
            }
            applyListPadding(fragment, context)
            return true
        }

        fun onChange(handler: (key: String, value: Boolean) -> Unit): Any =
            Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
                if (method.name == "onPreferenceChange") {
                    val pref = args?.getOrNull(0)
                    val newValue = args?.getOrNull(1) as? Boolean ?: return@newProxyInstance false
                    val key = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null } ?: return@newProxyInstance false
                    persist(key, newValue)
                    handler(key, newValue)
                    return@newProxyInstance true
                }
                false
            }

        fun openOnClick(pref: Any, intent: Intent) {
            val listener = Proxy.newProxyInstance(classLoader, arrayOf(clickListenerClass)) { _, method, _ ->
                if (method.name == "onPreferenceClick") {
                    try { context.startActivity(intent) } catch (_: Throwable) { return@newProxyInstance false }
                    return@newProxyInstance true
                }
                false
            }
            try { XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", listener) } catch (_: Throwable) {}
        }

        fun setEnabled(prefs: List<Any>, enabled: Boolean) {
            for (p in prefs) try { XposedHelpers.callMethod(p, "setEnabled", enabled) } catch (_: Throwable) {}
        }

        if (customIconsApp != null) {
            val pkg = customIconsApp
            val masterCategory = createCategory(isFirst = true)
            XposedHelpers.callMethod(screen, "addPreference", masterCategory)
            val channelsCategory = createCategory()
            XposedHelpers.callMethod(screen, "addPreference", channelsCategory)

            val appKey = ModulePreferences.customIconAppKey(pkg)
            val enableApp = customIconSwitch(appKey)
            val swApp = addSwitch(masterCategory, appKey, settingsCtx.getString(R.string.pref_custom_icons_master), null, enableApp, enableModule && enableCustomIcons)
            // The icons are black vectors: tint previews with the theme text color so they
            // stay visible in dark mode
            val previewTint = try {
                val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                a.getColorStateList(0).also { a.recycle() }
            } catch (_: Throwable) { null }
            val channelSwitches = customIconChannels(context, pkg).map { (channel, iconRes) ->
                val key = ModulePreferences.customIconChannelKey(pkg, channel.id)
                val checked = customIconSwitch(key)
                val sw = addSwitch(channelsCategory, key, channel.name?.toString() ?: channel.id, null, checked, enableModule && enableCustomIcons && enableApp)
                val preview = try { settingsCtx.getDrawable(iconRes)?.mutate() } catch (_: Throwable) { null }
                if (preview != null) {
                    if (previewTint != null) preview.setTintList(previewTint)
                    customIconPreviews[key] = preview
                }
                sw
            }
            applyUnifiedSectionStyle(listOf(swApp))
            applyUnifiedSectionStyle(channelSwitches)
            addSpacer(bottomSpacerLayoutRes)

            val listener = onChange { key, value -> if (key == appKey) setEnabled(channelSwitches, value) }
            for (sw in channelSwitches + swApp) {
                try { XposedHelpers.callMethod(sw, "setOnPreferenceChangeListener", listener) } catch (_: Throwable) {}
            }
            return finish()
        }

        if (customIconsScreen) {
            val masterCategory = createCategory(isFirst = true)
            XposedHelpers.callMethod(screen, "addPreference", masterCategory)
            val appsListCategory = createCategory()
            XposedHelpers.callMethod(screen, "addPreference", appsListCategory)

            val swMaster = addSwitch(masterCategory, ModulePreferences.KEY_ENABLE_CUSTOM_ICONS, settingsCtx.getString(R.string.pref_custom_icons_master), null, enableCustomIcons, enableModule)
            val pm = context.packageManager
            // The row shows the icon at its intrinsic size, which varies wildly between apps
            // (adaptive icons are ~108dp) - render every icon at one fixed size
            val appIconSize = (36 * context.resources.displayMetrics.density).toInt()
            fun fixedSizeIcon(d: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
                val bmp = android.graphics.Bitmap.createBitmap(appIconSize, appIconSize, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, appIconSize, appIconSize)
                d.draw(android.graphics.Canvas(bmp))
                return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
            }
            // Installed apps that have at least one category with an author icon
            val appPrefs = IconUtils.CUSTOM_ICON_PKGS.mapNotNull { pkg ->
                val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Throwable) { null } ?: return@mapNotNull null
                if (customIconChannels(context, pkg).isEmpty()) return@mapNotNull null
                val label = pm.getApplicationLabel(appInfo).toString()
                val pref = newJumpPreference()
                XposedHelpers.callMethod(pref, "setKey", "$CUSTOM_ICONS_ENTRY_KEY_PREFIX$pkg")
                XposedHelpers.callMethod(pref, "setTitle", label)
                try { XposedHelpers.callMethod(pref, "setIcon", fixedSizeIcon(pm.getApplicationIcon(appInfo))) } catch (_: Throwable) {}
                XposedHelpers.callMethod(pref, "setEnabled", enableModule && enableCustomIcons)
                XposedHelpers.callMethod(appsListCategory, "addPreference", pref)
                openOnClick(pref, subScreenIntent(label, Bundle().apply { putString(CUSTOM_ICONS_APP_MARKER, pkg) }))
                pref
            }
            applyUnifiedSectionStyle(listOf(swMaster))
            applyUnifiedSectionStyle(appPrefs)
            addSpacer(bottomSpacerLayoutRes)

            val listener = onChange { key, value -> if (key == ModulePreferences.KEY_ENABLE_CUSTOM_ICONS) setEnabled(appPrefs, value) }
            try { XposedHelpers.callMethod(swMaster, "setOnPreferenceChangeListener", listener) } catch (_: Throwable) {}
            return finish()
        }

        // Build categories
        val moduleCategory = createCategory(isFirst = true)
        XposedHelpers.callMethod(screen, "addPreference", moduleCategory)

        val behaviorCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", behaviorCategory)

        val appsCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", appsCategory)

        // Add switches
        val swModule = addSwitch(moduleCategory, ModulePreferences.KEY_ENABLE_MODULE, settingsCtx.getString(R.string.pref_enable_module), null, enableModule, true)
        val swStatus = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_STATUS_BAR, settingsCtx.getString(R.string.pref_status_bar), R.string.pref_status_bar_desc, enableStatusBar, enableModule)
        val swShade = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_SHADE, settingsCtx.getString(R.string.pref_shade), R.string.pref_shade_desc, enableShade, enableModule)
        val swAod = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_AOD, settingsCtx.getString(R.string.pref_aod), R.string.pref_aod_desc, enableAod, enableModule)
        val swNotificationCategories = addSwitch(
            appsCategory,
            ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
            settingsCtx.getString(R.string.pref_notification_categories),
            R.string.pref_notification_categories_desc,
            enableCategoryIcons,
            enableModule
        )
        // Opens the custom icons sub-screen (same SubSettings host as the main screen)
        val customIconsPref = newJumpPreference()
        val customIconsTitle = settingsCtx.getString(R.string.pref_custom_icons)
        XposedHelpers.callMethod(customIconsPref, "setTitle", customIconsTitle)
        XposedHelpers.callMethod(customIconsPref, "setSummary", settingsCtx.getString(R.string.pref_custom_icons_desc))
        XposedHelpers.callMethod(customIconsPref, "setEnabled", enableModule)
        XposedHelpers.callMethod(appsCategory, "addPreference", customIconsPref)
        openOnClick(customIconsPref, subScreenIntent(customIconsTitle, Bundle().apply { putBoolean(CUSTOM_ICONS_MARKER, true) }))

        applyUnifiedSectionStyle(listOf(swModule))
        applyUnifiedSectionStyle(listOf(swStatus, swShade, swAod))
        applyUnifiedSectionStyle(listOf(swNotificationCategories, customIconsPref))

        // Action category
        val actionCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", actionCategory)
        val restartPref = XposedHelpers.newInstance(prefClass, context)
        XposedHelpers.callMethod(restartPref, "setTitle", settingsCtx.getString(R.string.btn_restart_systemui))
        XposedHelpers.callMethod(restartPref, "setSummary", settingsCtx.getString(R.string.msg_saved))
        XposedHelpers.callMethod(actionCategory, "addPreference", restartPref)
        addSpacer(bottomSpacerLayoutRes)

        // Listeners
        val changeListener = Proxy.newProxyInstance(classLoader, arrayOf(listenerClass)) { _, method, args ->
            if (method.name == "onPreferenceChange") {
                val pref = args?.getOrNull(0)
                val newValue = args?.getOrNull(1) as? Boolean ?: return@newProxyInstance false
                val key = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null } ?: return@newProxyInstance false
                persist(key, newValue)
                if (key == ModulePreferences.KEY_ENABLE_MODULE) {
                    try { XposedHelpers.callMethod(swStatus, "setEnabled", newValue) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(swShade, "setEnabled", newValue) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(swAod, "setEnabled", newValue) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(swNotificationCategories, "setEnabled", newValue) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(customIconsPref, "setEnabled", newValue) } catch (_: Throwable) {}
                }
                return@newProxyInstance true
            }
            false
        }

        val restartListener = Proxy.newProxyInstance(classLoader, arrayOf(clickListenerClass)) { _, method, _ ->
            if (method.name == "onPreferenceClick") {
                clearCachesAndRestartSystemUi(context)
                return@newProxyInstance true
            }
            false
        }

        try { XposedHelpers.callMethod(swModule, "setOnPreferenceChangeListener", changeListener) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(swStatus, "setOnPreferenceChangeListener", changeListener) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(swShade, "setOnPreferenceChangeListener", changeListener) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(swAod, "setOnPreferenceChangeListener", changeListener) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(swNotificationCategories, "setOnPreferenceChangeListener", changeListener) } catch (_: Throwable) {}

        try { XposedHelpers.callMethod(restartPref, "setOnPreferenceClickListener", restartListener) } catch (_: Throwable) {}

        try { XposedHelpers.callMethod(fragment, "setPreferenceScreen", screen) } catch (_: Throwable) {
            XposedBridge.log("$TAG injectedSettings: setPreferenceScreen failed for ${fragment.javaClass.name}")
            return false
        }
        applyListPadding(fragment, context)
        XposedBridge.log("$TAG injectedSettings: built on ${fragment.javaClass.name}")
        return true
    }

    private fun subScreenIntent(title: String, args: Bundle): Intent = Intent().apply {
        setClassName("com.android.settings", "com.android.settings.SubSettings")
        putExtra(":settings:show_fragment", "com.android.settings.homepage.TopLevelSettings")
        putExtra(":settings:show_fragment_title", title)
        putExtra(":settings:show_fragment_args", args)
        putExtras(args)
    }

    private fun customIconsAppPkg(fragment: Any): String? {
        val args = try { XposedHelpers.callMethod(fragment, "getArguments") as? Bundle } catch (_: Throwable) { null }
        args?.getString(CUSTOM_ICONS_APP_MARKER)?.let { return it }
        args?.getBundle(":settings:show_fragment_args")?.getString(CUSTOM_ICONS_APP_MARKER)?.let { return it }
        val activity = try { XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity } catch (_: Throwable) { null }
        return activity?.intent?.getStringExtra(CUSTOM_ICONS_APP_MARKER)
    }

    /** Notification categories of [pkg] that get an author icon, with that icon. */
    private fun customIconChannels(context: Context, pkg: String): List<Pair<NotificationChannel, Int>> {
        return try {
            val binder = XposedHelpers.callStaticMethod(Class.forName("android.os.ServiceManager"), "getService", "notification")
            val nm = XposedHelpers.callStaticMethod(Class.forName("android.app.INotificationManager\$Stub"), "asInterface", binder)
            val uid = context.packageManager.getPackageUid(pkg, 0)
            val slice = XposedHelpers.callMethod(nm, "getNotificationChannelsForPackage", pkg, uid, false)
            @Suppress("UNCHECKED_CAST")
            val channels = XposedHelpers.callMethod(slice, "getList") as List<NotificationChannel>
            channels.mapNotNull { channel ->
                // Missed calls are matched by notification category at runtime; here only the
                // channel is known, so guess the category from it
                val category = if (channel.id.contains("missed", ignoreCase = true)) Notification.CATEGORY_MISSED_CALL else null
                IconUtils.customIconRes(pkg, channel.id, category)?.let { channel to it }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG customIconChannels($pkg): ${t.message}")
            emptyList()
        }
    }

    private fun isCustomIconsScreen(fragment: Any): Boolean {
        val args = try { XposedHelpers.callMethod(fragment, "getArguments") as? Bundle } catch (_: Throwable) { null }
        if (args?.getBoolean(CUSTOM_ICONS_MARKER, false) == true) return true
        if (args?.getBundle(":settings:show_fragment_args")?.getBoolean(CUSTOM_ICONS_MARKER, false) == true) return true
        val activity = try { XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity } catch (_: Throwable) { null }
        return activity?.intent?.getBooleanExtra(CUSTOM_ICONS_MARKER, false) == true
    }

    private fun isInjectedSettingsScreen(fragment: Any): Boolean {
        val args = try { XposedHelpers.callMethod(fragment, "getArguments") as? Bundle } catch (_: Throwable) { null }
        if (args?.getBoolean(MODULE_SETTINGS_MARKER, false) == true) return true
        if (args?.getBundle(":settings:show_fragment_args")?.getBoolean(MODULE_SETTINGS_MARKER, false) == true) return true

        val activity = try { XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity } catch (_: Throwable) { null }
        val intent = activity?.intent
        if (intent?.getBooleanExtra(MODULE_SETTINGS_MARKER, false) == true) return true
        if (intent?.getBundleExtra(":settings:show_fragment_args")?.getBoolean(MODULE_SETTINGS_MARKER, false) == true) return true
        val title = (intent?.getStringExtra(":settings:show_fragment_title") ?: "").trim()
        if (title.equals("Material icons", ignoreCase = true) || title.equals("Иконки Material", ignoreCase = true)) return true
        if (title.contains("material", ignoreCase = true) || title.contains("иконки", ignoreCase = true)) return true
        return false
    }

    private fun applyListPadding(fragment: Any, context: Context) {
        try {
            val view = (try { XposedHelpers.callMethod(fragment, "getView") as? View } catch (_: Throwable) { null })
                ?: (try {
                    val activity = XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
                    activity?.window?.decorView
                } catch (_: Throwable) { null })
                ?: return
            val list = findListView(view) ?: return
            val density = context.resources.displayMetrics.density
            val bottomExtra = (56f * density).toInt()
            val topAdjust = (-2f * density).toInt()
            val left = list.paddingLeft
            val right = list.paddingRight
            val top = topAdjust
            val bottom = max(list.paddingBottom, bottomExtra)
            list.setPadding(left, top, right, bottom)
            try {
                XposedHelpers.callMethod(list, "setClipToPadding", false)
            } catch (_: Throwable) {}
            try { list.requestLayout() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun findListView(root: View?): View? {
        val view = root ?: return null
        if (view.id == android.R.id.list) return view
        if (view.javaClass.name.contains("RecyclerView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val candidate = findListView(view.getChildAt(i))
                if (candidate != null) return candidate
            }
        }
        return null
    }

    // ── Read preferences ───────────────────────────────────────────────

    private fun readPrefs(resolver: ContentResolver): Map<String, Boolean> {
        readFromSettingsGlobal(resolver)?.let { return it }
        readFromFile(PreferenceUtils.PREFS_PATH)?.let { return it }
        readViaSu()?.let { return it }
        return emptyMap()
    }

    private fun readFromSettingsGlobal(resolver: ContentResolver): Map<String, Boolean>? {
        return try {
            val map = mutableMapOf<String, Boolean>()
            for (key in KNOWN_KEYS) {
                val v = Settings.Global.getInt(resolver, PreferenceUtils.globalKey(key), -1)
                if (v != -1) map[key] = v == 1
            }
            if (map.isNotEmpty()) map else null
        } catch (t: Throwable) {
            XposedBridge.log("$TAG readPrefs(Global): ${t.message}")
            null
        }
    }

    private val boolRegex = Regex("""<boolean name="([^"]+)" value="([^"]+)"""")

    private fun readFromFile(path: String): Map<String, Boolean>? {
        return try {
            val file = File(path)
            if (!file.canRead()) return null
            val xml = file.readText()
            val map = mutableMapOf<String, Boolean>()
            for (match in boolRegex.findAll(xml)) {
                map[match.groupValues[1]] = match.groupValues[2].toBoolean()
            }
            if (map.isNotEmpty()) map else null
        } catch (_: Throwable) { null }
    }

    private fun readViaSu(): Map<String, Boolean>? {
        for (path in listOf(PreferenceUtils.PREFS_PATH, findLsposedPrefsFile())) {
            if (path == null) continue
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "cat '$path' 2>/dev/null")
                )
                val xml = process.inputStream.bufferedReader().readText()
                process.waitFor()
                val map = mutableMapOf<String, Boolean>()
                for (match in boolRegex.findAll(xml)) {
                    map[match.groupValues[1]] = match.groupValues[2].toBoolean()
                }
                if (map.isNotEmpty()) return map
            } catch (_: Throwable) {}
        }
        return null
    }

    // ── Write preferences ──────────────────────────────────────────────

    private fun writePrefs(resolver: ContentResolver, prefs: Map<String, Boolean>) {
        writeToSettingsGlobal(resolver, prefs)
        writeViaSuBestEffort(prefs)
    }

    private fun writeToSettingsGlobal(resolver: ContentResolver, prefs: Map<String, Boolean>) {
        try {
            for ((key, value) in prefs) {
                Settings.Global.putInt(resolver, PreferenceUtils.globalKey(key), if (value) 1 else 0)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG writePrefs(Global): ${t.message}")
        }
    }

    private fun writeViaSuBestEffort(prefs: Map<String, Boolean>) {
        val xml = buildPrefsXml(prefs)

        try {
            val sysFile = PreferenceUtils.PREFS_PATH
            val process = ProcessBuilder(
                "su", "-c",
                "cat > '$sysFile' && chmod 644 '$sysFile'"
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(xml) }
            process.waitFor()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG writePrefs(sys-file): ${t.message}")
        }

        @Suppress("DEPRECATION")
        try {
            val legacy = PreferenceUtils.LEGACY_PREFS_PATH
            val process = ProcessBuilder(
                "su", "-c",
                "cat > '$legacy' && chmod 644 '$legacy'"
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(xml) }
            process.waitFor()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG writePrefs(legacy-file): ${t.message}")
        }

        val lsposedFile = findLsposedPrefsFile()
        if (lsposedFile != null) {
            try {
                val dir = lsposedFile.substringBeforeLast('/')
                val script =
                    "OWNER=\"\$(stat -c '%u:%g' '$dir')\" && " +
                    "cat > '$lsposedFile' && " +
                    "chown \"\$OWNER\" '$lsposedFile' && " +
                    "chmod 660 '$lsposedFile' && " +
                    "chcon u:object_r:xposed_data:s0 '$lsposedFile' 2>/dev/null"
                val process = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { it.write(xml) }
                process.waitFor()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG writePrefs(lspd-file): ${t.message}")
            }
        }
    }

    private fun findLsposedPrefsFile(): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c",
                    "ls /data/misc/*/prefs/$MODULE_PKG/$PREF_FILENAME 2>/dev/null | head -1")
            )
            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            path.ifEmpty { null }
        } catch (_: Throwable) { null }
    }

    private fun buildPrefsXml(prefs: Map<String, Boolean>): String {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((key, value) in prefs) {
            sb.append("    <boolean name=\"$key\" value=\"$value\" />\n")
        }
        sb.append("</map>\n")
        return sb.toString()
    }

    // ── Restart SystemUI ───────────────────────────────────────────────

    private fun clearCachesAndRestartSystemUi(context: Context) {
        try { IconUtils.clearAllCaches() } catch (_: Throwable) {}
        if (tryRestartViaSu()) return
        if (tryRestartViaActivityManager(context)) return
        tryRestartViaAmCommand()
    }

    private fun tryRestartViaSu(): Boolean {
        val commands = listOf(
            "pkill -f com.android.systemui",
            "killall com.android.systemui",
            "am force-stop com.android.systemui"
        )
        for (command in commands) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                p.waitFor()
                if (p.exitValue() == 0) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun tryRestartViaActivityManager(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.javaClass.getMethod("forceStopPackage", String::class.java)
                .invoke(am, "com.android.systemui")
            true
        } catch (_: Throwable) { false }
    }

    private fun tryRestartViaAmCommand(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("am", "force-stop", "com.android.systemui"))
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Throwable) { false }
    }

    private val DEFAULTS: Map<String, Boolean> = mapOf(
        ModulePreferences.KEY_ENABLE_MODULE to ModulePreferences.DEFAULT_ENABLE_MODULE,
        ModulePreferences.KEY_ENABLE_STATUS_BAR to ModulePreferences.DEFAULT_ENABLE_STATUS_BAR,
        ModulePreferences.KEY_ENABLE_SHADE to ModulePreferences.DEFAULT_ENABLE_SHADE,
        ModulePreferences.KEY_ENABLE_AOD to ModulePreferences.DEFAULT_ENABLE_AOD,
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS to ModulePreferences.DEFAULT_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.KEY_ENABLE_CUSTOM_ICONS to ModulePreferences.DEFAULT_ENABLE_CUSTOM_ICONS
    )

    private val KNOWN_KEYS = DEFAULTS.keys
}
