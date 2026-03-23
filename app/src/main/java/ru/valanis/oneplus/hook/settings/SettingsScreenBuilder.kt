package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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

    private const val MODULE_PKG = PreferenceUtils.MODULE_PKG
    private const val PREF_FILENAME = "${ModulePreferences.PREF_FILE}.xml"

    fun tryBuild(fragment: Any): Boolean {
        if (!isInjectedSettingsScreen(fragment)) return false
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
        fun prefBool(key: String, default: Boolean) = savedPrefs[key] ?: default

        val enableModule = prefBool(ModulePreferences.KEY_ENABLE_MODULE, ModulePreferences.DEFAULT_ENABLE_MODULE)
        val enableStatusBar = prefBool(ModulePreferences.KEY_ENABLE_STATUS_BAR, ModulePreferences.DEFAULT_ENABLE_STATUS_BAR)
        val enableShade = prefBool(ModulePreferences.KEY_ENABLE_SHADE, ModulePreferences.DEFAULT_ENABLE_SHADE)
        val enableAod = prefBool(ModulePreferences.KEY_ENABLE_AOD, ModulePreferences.DEFAULT_ENABLE_AOD)

        val currentPrefs = mutableMapOf(
            ModulePreferences.KEY_ENABLE_MODULE to enableModule,
            ModulePreferences.KEY_ENABLE_STATUS_BAR to enableStatusBar,
            ModulePreferences.KEY_ENABLE_SHADE to enableShade,
            ModulePreferences.KEY_ENABLE_AOD to enableAod,
            ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS to false,
            ModulePreferences.KEY_ENABLE_CUSTOM_ICONS to false
        )

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

        fun addSwitch(
            targetCategory: Any,
            key: String,
            titleRes: Int,
            summaryRes: Int?,
            checked: Boolean,
            enabled: Boolean
        ): Any {
            val sw = newSwitchPreference()
            XposedHelpers.callMethod(sw, "setKey", key)
            XposedHelpers.callMethod(sw, "setTitle", settingsCtx.getString(titleRes))
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

        // Build categories
        val moduleCategory = createCategory(isFirst = true)
        XposedHelpers.callMethod(screen, "addPreference", moduleCategory)

        val behaviorCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", behaviorCategory)

        val appsCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", appsCategory)

        // Add switches
        val swModule = addSwitch(moduleCategory, ModulePreferences.KEY_ENABLE_MODULE, R.string.pref_enable_module, null, enableModule, true)
        val swStatus = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_STATUS_BAR, R.string.pref_status_bar, R.string.pref_status_bar_desc, enableStatusBar, enableModule)
        val swShade = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_SHADE, R.string.pref_shade, R.string.pref_shade_desc, enableShade, enableModule)
        val swAod = addSwitch(behaviorCategory, ModulePreferences.KEY_ENABLE_AOD, R.string.pref_aod, R.string.pref_aod_desc, enableAod, enableModule)
        val comingSoon = settingsCtx.getString(R.string.pref_coming_soon)
        val swNotificationCategories = addSwitch(appsCategory, ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS, R.string.pref_notification_categories, R.string.pref_notification_categories_desc, false, false)
        try { XposedHelpers.callMethod(swNotificationCategories, "setSummary", settingsCtx.getString(R.string.pref_notification_categories_desc) + "\n\n" + comingSoon) } catch (_: Throwable) {}
        val swCustom = addSwitch(appsCategory, ModulePreferences.KEY_ENABLE_CUSTOM_ICONS, R.string.pref_custom_icons, R.string.pref_custom_icons_desc, false, false)
        try { XposedHelpers.callMethod(swCustom, "setSummary", settingsCtx.getString(R.string.pref_custom_icons_desc) + "\n\n" + comingSoon) } catch (_: Throwable) {}

        applyUnifiedSectionStyle(listOf(swModule))
        applyUnifiedSectionStyle(listOf(swStatus, swShade, swAod))
        applyUnifiedSectionStyle(listOf(swNotificationCategories, swCustom))

        // Action category
        val actionCategory = createCategory()
        XposedHelpers.callMethod(screen, "addPreference", actionCategory)
        val restartPref = XposedHelpers.newInstance(prefClass, context)
        XposedHelpers.callMethod(restartPref, "setTitle", settingsCtx.getString(R.string.btn_restart_systemui))
        XposedHelpers.callMethod(restartPref, "setSummary", settingsCtx.getString(R.string.msg_saved))
        XposedHelpers.callMethod(actionCategory, "addPreference", restartPref)
        addSpacer(bottomSpacerLayoutRes)

        // Listeners
        fun persist(key: String, value: Boolean) {
            currentPrefs[key] = value
            writePrefs(context.contentResolver, currentPrefs)
        }

        val listenerClass = XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", classLoader)
        val clickListenerClass = XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", classLoader)

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

        try { XposedHelpers.callMethod(restartPref, "setOnPreferenceClickListener", restartListener) } catch (_: Throwable) {}

        try { XposedHelpers.callMethod(fragment, "setPreferenceScreen", screen) } catch (_: Throwable) {
            XposedBridge.log("$TAG injectedSettings: setPreferenceScreen failed for ${fragment.javaClass.name}")
            return false
        }
        applyListPadding(fragment, context)
        XposedBridge.log("$TAG injectedSettings: built on ${fragment.javaClass.name}")
        return true
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

    private val KNOWN_KEYS = listOf(
        ModulePreferences.KEY_ENABLE_MODULE,
        ModulePreferences.KEY_ENABLE_STATUS_BAR,
        ModulePreferences.KEY_ENABLE_SHADE,
        ModulePreferences.KEY_ENABLE_AOD,
        ModulePreferences.KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS,
        ModulePreferences.KEY_ENABLE_CUSTOM_ICONS
    )
}
