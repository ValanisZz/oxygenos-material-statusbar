package ru.valanis.oneplus.hook.settings

import android.content.Context
import de.robv.android.xposed.XposedHelpers
import kotlin.math.max

object PreferenceHelper {

    fun findByKeys(group: Any, keys: List<String>): Any? {
        for (key in keys) {
            val found = findByKey(group, key)
            if (found != null) return found
        }
        return null
    }

    fun findByKey(group: Any, key: String): Any? {
        val prefCount = try { XposedHelpers.callMethod(group, "getPreferenceCount") as? Int } catch (_: Throwable) { null } ?: return null
        for (i in 0 until prefCount) {
            val pref = try { XposedHelpers.callMethod(group, "getPreference", i) } catch (_: Throwable) { null } ?: continue
            val prefKey = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null }
            if (prefKey == key) return pref
            val nested = findByKey(pref, key)
            if (nested != null) return nested
        }
        return null
    }

    fun findByTitles(group: Any, titleCandidates: List<String>): Any? {
        val prefCount = try { XposedHelpers.callMethod(group, "getPreferenceCount") as? Int } catch (_: Throwable) { null } ?: return null
        for (i in 0 until prefCount) {
            val pref = try { XposedHelpers.callMethod(group, "getPreference", i) } catch (_: Throwable) { null } ?: continue
            val title = try { XposedHelpers.callMethod(pref, "getTitle")?.toString() } catch (_: Throwable) { null } ?: ""
            if (titleCandidates.any { candidate -> title.contains(candidate, ignoreCase = true) }) {
                return pref
            }
            val nested = findByTitles(pref, titleCandidates)
            if (nested != null) return nested
        }
        return null
    }

    fun firstChild(group: Any): Any? {
        val prefCount = try { XposedHelpers.callMethod(group, "getPreferenceCount") as? Int } catch (_: Throwable) { null } ?: return null
        if (prefCount <= 0) return null
        return try { XposedHelpers.callMethod(group, "getPreference", 0) } catch (_: Throwable) { null }
    }

    fun maxChildOrder(group: Any): Int? {
        val prefCount = try { XposedHelpers.callMethod(group, "getPreferenceCount") as? Int } catch (_: Throwable) { null } ?: return null
        if (prefCount <= 0) return null
        var maxOrder: Int? = null
        for (i in 0 until prefCount) {
            val pref = try { XposedHelpers.callMethod(group, "getPreference", i) } catch (_: Throwable) { null } ?: continue
            val order = try { XposedHelpers.callMethod(pref, "getOrder") as? Int } catch (_: Throwable) { null } ?: continue
            maxOrder = if (maxOrder == null) order else max(maxOrder, order)
        }
        return maxOrder
    }

    fun newPreferenceLike(anchor: Any, context: Context, classLoader: ClassLoader): Any {
        return try {
            val ctor = anchor.javaClass.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(context)
        } catch (_: Throwable) {
            val prefClass = XposedHelpers.findClass("androidx.preference.Preference", classLoader)
            XposedHelpers.newInstance(prefClass, context)
        }
    }

    fun copyVisualStyle(anchor: Any, target: Any) {
        copyProperty(anchor, target, "getLayoutResource", "setLayoutResource")
        copyProperty(anchor, target, "getWidgetLayoutResource", "setWidgetLayoutResource")
        copyProperty(anchor, target, "isIconSpaceReserved", "setIconSpaceReserved")
        copyProperty(anchor, target, "isSingleLineTitle", "setSingleLineTitle")
        copyProperty(anchor, target, "isPersistent", "setPersistent")
        copyProperty(anchor, target, "isSelectable", "setSelectable")

        copyAnyProperty(anchor, target, listOf("getLayoutCategory"), listOf("setLayoutCategory"))
        copyAnyProperty(anchor, target, listOf("isNeedChangeDrawType", "getNeedChangeDrawType"), listOf("setNeedChangeDrawType"))
        copyAnyProperty(anchor, target, listOf("isShowSummary", "getShowSummary"), listOf("setShowSummary"))
        copyAnyProperty(anchor, target, listOf("isShowTwoToneColor", "getShowTwoToneColor"), listOf("setShowTwoToneColor"))
        copyAnyProperty(anchor, target, listOf("getTintType"), listOf("setTintType"))
        copyAnyProperty(anchor, target, listOf("getTintIconNew"), listOf("setTintIconNew"))
    }

    private fun copyProperty(source: Any, target: Any, getter: String, setter: String) {
        try {
            val value = XposedHelpers.callMethod(source, getter)
            XposedHelpers.callMethod(target, setter, value)
        } catch (_: Throwable) {}
    }

    private fun copyAnyProperty(source: Any, target: Any, getters: List<String>, setters: List<String>) {
        for (getter in getters) {
            val value = try { XposedHelpers.callMethod(source, getter) } catch (_: Throwable) { null } ?: continue
            for (setter in setters) {
                try {
                    XposedHelpers.callMethod(target, setter, value)
                    return
                } catch (_: Throwable) {}
            }
        }
    }
}
