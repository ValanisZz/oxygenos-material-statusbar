package ru.valanis.oneplus.statusbar.material.settings


object ModulePreferences {

    const val PREF_FILE = "module_settings"

    const val KEY_ENABLE_MODULE = "enable_module"
    const val KEY_ENABLE_STATUS_BAR = "enable_status_bar"
    const val KEY_ENABLE_SHADE = "enable_shade"
    const val KEY_ENABLE_AOD = "enable_aod"
    const val KEY_ENABLE_NOTIFICATION_CATEGORY_ICONS = "enable_notification_category_icons"
    const val KEY_ENABLE_CUSTOM_ICONS = "enable_custom_icons"

    const val DEFAULT_ENABLE_MODULE = true
    const val DEFAULT_ENABLE_STATUS_BAR = true
    const val DEFAULT_ENABLE_SHADE = true
    const val DEFAULT_ENABLE_AOD = true
    const val DEFAULT_ENABLE_NOTIFICATION_CATEGORY_ICONS = false
    const val DEFAULT_ENABLE_CUSTOM_ICONS = true
    const val DEFAULT_ENABLE_CUSTOM_ICON = true

    /** Per-app and per-category switches on the custom icons screens (default on). */
    fun customIconAppKey(pkg: String): String = "custom_icon_app_${safeKey(pkg)}"
    fun customIconChannelKey(pkg: String, channelId: String): String =
        "custom_icon_ch_${safeKey(pkg)}/${safeKey(channelId)}"

    // Keys end up as XML attributes and Settings.Global names
    private fun safeKey(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

}