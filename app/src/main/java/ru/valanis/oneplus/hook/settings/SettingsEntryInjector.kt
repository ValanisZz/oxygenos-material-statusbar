package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import ru.valanis.oneplus.hook.core.PreferenceUtils
import ru.valanis.oneplus.statusbar.material.R
import java.lang.reflect.Proxy
import kotlin.math.max

@SuppressLint("PrivateApi")
object SettingsEntryInjector {

    private const val MODULE_ENTRY_KEY = "statusbar_aod_icons_entry"
    private const val MODULE_SETTINGS_MARKER = "oxygen_material_module_settings_screen"

    private val SETTINGS_ANCHOR_KEYS = listOf(
        "notification_and_statusbar",
        "top_level_notifications"
    )
    private val SETTINGS_ANCHOR_CATEGORY_KEYS = listOf(
        "notification_settings_category"
    )
    private val SETTINGS_ANCHOR_TITLE_CANDIDATES = listOf(
        "Уведомления и быстрые настройки",
        "Notifications & quick settings",
        "Notifications and quick settings",
        "Уведомления и строка состояния",
        "Notifications and status bar"
    )

    fun inject(fragment: Any) {
        val context = try { XposedHelpers.callMethod(fragment, "getContext") as? Context } catch (_: Throwable) { null } ?: return
        val screen = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null } ?: return

        if (PreferenceHelper.findByKey(screen, MODULE_ENTRY_KEY) != null) return

        val anchor = PreferenceHelper.findByKeys(screen, SETTINGS_ANCHOR_KEYS)
            ?: PreferenceHelper.findByTitles(screen, SETTINGS_ANCHOR_TITLE_CANDIDATES)
        val categoryFallback = PreferenceHelper.findByKeys(screen, SETTINGS_ANCHOR_CATEGORY_KEYS)
        if (anchor == null && categoryFallback == null) return
        val parentGroup = when {
            anchor != null -> try {
                XposedHelpers.callMethod(anchor, "getParent")
            } catch (_: Throwable) { null }
            categoryFallback != null -> categoryFallback
            else -> screen
        } ?: screen

        val moduleContext = try {
            context.createPackageContext(PreferenceUtils.MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Throwable) { null } ?: context
        val entryTitle = try { moduleContext.getString(R.string.settings_entry_title) } catch (_: Throwable) { "Material icons" }
        val styleSource = anchor ?: PreferenceHelper.firstChild(parentGroup) ?: return
        val anchorOrder = try { anchor?.let { XposedHelpers.callMethod(it, "getOrder") as? Int } } catch (_: Throwable) { null }
        val fallbackOrder = PreferenceHelper.maxChildOrder(parentGroup)
        val classLoader = fragment.javaClass.classLoader ?: return
        val preference = PreferenceHelper.newPreferenceLike(styleSource, context, classLoader)

        PreferenceHelper.copyVisualStyle(styleSource, preference)
        XposedHelpers.callMethod(preference, "setKey", MODULE_ENTRY_KEY)
        XposedHelpers.callMethod(preference, "setTitle", entryTitle)
        val targetOrder = (anchorOrder?.plus(1)) ?: fallbackOrder?.plus(1)
        targetOrder?.let { XposedHelpers.callMethod(preference, "setOrder", it) }
        setModuleEntryIcon(preference, moduleContext)

        val launchArgs = Bundle().apply {
            putBoolean(MODULE_SETTINGS_MARKER, true)
        }
        val launchIntent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:show_fragment", "com.android.settings.homepage.TopLevelSettings")
            putExtra(":settings:show_fragment_title", entryTitle)
            putExtra(":settings:show_fragment_args", launchArgs)
            putExtra(MODULE_SETTINGS_MARKER, true)
        }
        XposedHelpers.callMethod(preference, "setIntent", launchIntent)
        try {
            val clickListenerClass = XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceClickListener",
                classLoader
            )
            val clickListener = Proxy.newProxyInstance(
                classLoader,
                arrayOf(clickListenerClass)
            ) { _, method, _ ->
                if (method.name == "onPreferenceClick") {
                    try {
                        context.startActivity(launchIntent)
                        return@newProxyInstance true
                    } catch (_: Throwable) {
                        return@newProxyInstance false
                    }
                }
                false
            }
            XposedHelpers.callMethod(preference, "setOnPreferenceClickListener", clickListener)
        } catch (_: Throwable) {}
        XposedHelpers.callMethod(parentGroup, "addPreference", preference)
    }

    private fun setModuleEntryIcon(preference: Any, moduleContext: Context) {
        val icon: Drawable? = try {
            moduleContext.getDrawable(R.drawable.ic_settings_statusbar_aod)
        } catch (_: Throwable) { null }
        if (icon != null) {
            try { XposedHelpers.callMethod(preference, "setIcon", icon) } catch (_: Throwable) {}
        }
    }
}
