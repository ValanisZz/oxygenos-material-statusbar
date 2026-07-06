package ru.valanis.oneplus.hook.settings

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.app.NotificationManager
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import ru.valanis.oneplus.hook.core.ChannelIconStore
import ru.valanis.oneplus.hook.core.PreferenceUtils
import ru.valanis.oneplus.statusbar.material.ChannelIconJsonWriter
import ru.valanis.oneplus.statusbar.material.R
import kotlin.math.max

/**
 * Injects "Icon" into **per-channel** notification settings only.
 *
 * Must not run on app-level notification screens (same toggle labels, different fragment).
 * Inserts one [Preference] at the **end of the same PreferenceCategory** that contains
 * "Allow notifications" / "Silent mode" so COUI section styling is not split.
 */
@SuppressLint("PrivateApi")
object ChannelNotificationIconInjector {

    private const val TAG = "OxygenOSMaterialIcons"
    private const val PREF_KEY_ICON_ENABLED    = "oxygen_material_icon_enabled"
    private const val PREF_KEY_ICON_PATH       = "oxygen_material_icon_path"
    private const val PREF_KEY_ICON_PATH_SHADE = "oxygen_material_icon_path_shade"
    private const val PREF_KEY_ICON_PATH_AOD   = "oxygen_material_icon_path_aod"
    private const val PREF_KEY_ICON_MONO       = "oxygen_material_icon_mono"

    /** Request code for the system file picker launched from within the injected preference. */
    const val FILE_PICKER_REQUEST_CODE = 0x4943  // "IC" = Icon Chooser

    /** Pending callback set before startActivityForResult; invoked with the resolved file path. */
    @Volatile
    var pendingFilePickerCallback: ((String) -> Unit)? = null

    /** Keys of the three icon picker preferences — used by the onBindViewHolder hook. */
    val ICON_PREF_KEYS = setOf(
        PREF_KEY_ICON_PATH,
        PREF_KEY_ICON_PATH_SHADE,
        PREF_KEY_ICON_PATH_AOD
    )

    /** Drawable preview for each picker pref key; null = no preview. Updated by trySetIconPreview. */
    val iconPreviews = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable?>()

    /** Oplus: [NotificationChannel] подгружается после первого onResume — один раз ставим отложенные повторы. */
    private val oplusChannelRetryScheduled =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * App-level «все уведомления приложения»: класс содержит AppNotification / Manage… и т.п.,
     * но не «один канал» (ChannelNotificationSettings).
     * Не полагаться только на длинные имена — у OEM они другие.
     */
    private val APP_LEVEL_EXTRA_MARKERS = listOf(
        "AppNotificationManagement",
        "ApplicationNotification",
        "NotificationAppSettings",
        "AllNotificationsSettings",
        "BulkNotificationsSettings",
        "ManageAppNotification",
        "NotificationList"
    )

    /** First row: allow / block — без ключей, которые встречаются на списке каналов приложения. */
    private val FIRST_ROW_KEYS = listOf(
        "block",
        "allow_notifications",
        "notification_switch",
        "pref_allow_notifications",
        "show_notification"
    )

    /**
     * Ключи, которые указывают на сводку «все уведомления приложения» / список каналов.
     * Не включать ключи, которые встречаются на экране ОДНОГО канала у OEM (ложный app-level).
     */
    private val APP_LEVEL_SCREEN_KEYS = listOf(
        "channels",
        "channel_list",
        "notification_channels",
        "all_notifications",
        "app_notifications",
        "conversations"
    )

    /**
     * Keys that must not be used as channel id (groups, legacy mistakes).
     */
    private val CHANNEL_ID_EXCLUDE_PREFIXES = listOf(
        "group_",
        "summary_"
    )

    /**
     * @param knownChannelActivity true если вызвано из Activity, в имени которой уже есть
     *   "ChannelNotificationSettings" — проверки `isAppLevel` и `isAllowedSingleChannelScreen`
     *   можно пропустить (generic фрагмент вроде `PreferenceFragmentEmpty` их не проходит).
     */
    fun maybeInject(fragment: Any, fromRetry: Boolean = false, knownChannelActivity: Boolean = false) {
        if (!PreferenceUtils.isModuleEnabled()) return
        if (!PreferenceUtils.isNotificationCategoryIconsEnabled()) {
            val cn = fragment.javaClass.name
            if (cn.contains("ChannelNotification", ignoreCase = true) ||
                cn.contains("ConversationNotification", ignoreCase = true) ||
                knownChannelActivity
            ) {
                XposedBridge.log("$TAG channelIcon: abort notification category icons disabled in prefs ($cn)")
            }
            return
        }
        val className = fragment.javaClass.name
        val screen = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null }
        if (!knownChannelActivity && isAppLevelNotificationScreen(className, screen, fragment)) {
            if (shouldLogChannelIconDiag(className)) {
                XposedBridge.log("$TAG channelIcon: abort classified as app-level summary ($className)")
            }
            return
        }
        val pkg = extractPackageName(fragment)
        val channelId = extractChannelId(fragment)

        if (knownChannelActivity && pkg.isNullOrEmpty()) {
            val actPkg = extractPackageNameFromParentActivity(fragment)
            if (!actPkg.isNullOrEmpty()) {
                val actCh = channelId ?: extractChannelIdFromParentActivity(fragment)
                if (!actCh.isNullOrEmpty()) {
                    XposedBridge.log("$TAG channelIcon: using activity pkg=$actPkg channelId=$actCh")
                    try { injectInternal(fragment, actPkg, actCh) } catch (t: Throwable) {
                        XposedBridge.log("$TAG channelIcon inject: ${t.message}")
                    }
                    return
                }
            }
        }

        if (pkg.isNullOrEmpty()) {
            XposedBridge.log("$TAG channelIcon: skip class=$className pkg=null channelId=$channelId")
            return
        }
        if (channelId.isNullOrEmpty()) {
            if (!fromRetry && (knownChannelActivity || shouldDeferOplusChannelIdRetry(className))) {
                scheduleOplusChannelIdRetries(fragment, knownChannelActivity)
                XposedBridge.log("$TAG channelIcon: defer async channel load pkg=$pkg (retry scheduled)")
                return
            }
            if (!fromRetry) {
                XposedBridge.log("$TAG channelIcon: skip class=$className pkg=$pkg channelId=null")
            }
            return
        }
        if (!knownChannelActivity && !isAllowedSingleChannelScreen(className, fragment, screen, channelId)) {
            XposedBridge.log("$TAG channelIcon: skip not single-channel UI ($className)")
            return
        }

        try {
            injectInternal(fragment, pkg, channelId)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG channelIcon inject: ${t.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // File picker result handling
    // ──────────────────────────────────────────────────────────────────────────

    fun handleFilePickerResult(activity: android.app.Activity, resultCode: Int, data: Intent?) {
        val callback = pendingFilePickerCallback ?: return
        pendingFilePickerCallback = null
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        // Run I/O on a background thread; deliver result on main thread.
        Thread {
            val originalPath = resolveUriToPath(activity, uri)
            // Copy to /data/system/ so SystemUI/AOD processes can read it.
            val systemPath = copyToSystemIcons(activity, uri, originalPath)
            Handler(Looper.getMainLooper()).post {
                callback(systemPath ?: originalPath ?: uri.toString())
            }
        }.start()
    }

    /**
     * Copies the selected icon file to /data/system/[MODULE_PKG]_icons/ via root so that
     * SystemUI and com.oplus.aod processes (which have no access to external storage) can
     * read it.  Returns the system-side absolute path on success, null on failure.
     */
    private fun copyToSystemIcons(context: Context, uri: Uri, originalPath: String?): String? {
        // Determine extension from original path or MIME type.
        val ext = originalPath
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..5 }
            ?: try {
                val mime = context.contentResolver.getType(uri) ?: ""
                when {
                    mime.contains("svg") -> "svg"
                    mime.contains("xml") -> "xml"
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                    else -> "bin"
                }
            } catch (_: Throwable) { "bin" }

        // Read file bytes — ContentResolver has the URI permission in this (Settings) process.
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) { null }
            ?: originalPath?.let { try { java.io.File(it).readBytes() } catch (_: Throwable) { null } }
            ?: return null

        val dir = "/data/system/${PreferenceUtils.MODULE_PKG}_icons"
        val name = "icon_${(uri.toString().hashCode() and 0x7FFFFFFF)}.$ext"
        val destPath = "$dir/$name"

        return try {
            val process = ProcessBuilder(
                "su", "-c",
                "mkdir -p '$dir' && chmod 755 '$dir' && cat > '$destPath' && chmod 644 '$destPath'"
            ).redirectErrorStream(true).start()
            process.outputStream.use { it.write(bytes) }
            val exitCode = process.waitFor()
            if (exitCode == 0) destPath else null
        } catch (_: Throwable) { null }
    }

    private fun resolveUriToPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size == 2 && split[0].equals("primary", ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                }
            }
        } catch (_: Throwable) {}
        try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun trySetIconPreview(pref: Any, path: String, context: Context) {
        val key = try { XposedHelpers.callMethod(pref, "getKey") as? String } catch (_: Throwable) { null }
        val drawable = if (path.isNotEmpty()) loadDrawableFromPath(path, context) else null
        if (key != null) iconPreviews[key] = drawable
        try { XposedHelpers.callMethod(pref, "notifyChanged") } catch (_: Throwable) {}
    }

    private fun loadDrawableFromPath(path: String, context: Context): android.graphics.drawable.Drawable? =
        ru.valanis.oneplus.hook.core.IconUtils.loadDrawableFromXmlPath(path, context)

    fun syncVisibility(fragment: Any) {
        if (!PreferenceUtils.isModuleEnabled()) return
        if (!PreferenceUtils.isNotificationCategoryIconsEnabled()) return
        val className = fragment.javaClass.name
        val screen = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null }
        if (isAppLevelNotificationScreen(className, screen, fragment)) return
        val pkg = extractPackageName(fragment) ?: return
        val channelId = extractChannelId(fragment) ?: return
        if (!isAllowedSingleChannelScreen(className, fragment, screen, channelId)) return
        val context = try { XposedHelpers.callMethod(fragment, "getContext") as? Context } catch (_: Throwable) { null } ?: return
        val prefScreen = screen ?: return
        val allowed = isChannelNotificationsAllowed(context, pkg, channelId)
        for (key in listOf(PREF_KEY_ICON_ENABLED, PREF_KEY_ICON_PATH, PREF_KEY_ICON_PATH_SHADE, PREF_KEY_ICON_PATH_AOD, PREF_KEY_ICON_MONO)) {
            val p = PreferenceHelper.findByKey(prefScreen, key) ?: continue
            try { XposedHelpers.callMethod(p, "setVisible", allowed) } catch (_: Throwable) {}
        }
    }

    /**
     * На разных сборках Oplus путь к классу разный: `...fragments.channel...`, `...channel...` без `fragments`.
     * Должно совпадать с фильтром в [SettingsHook] (иначе defer не сработает и останется только skip).
     */
    private fun extractPackageNameFromParentActivity(fragment: Any): String? {
        val act = try {
            XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
        } catch (_: Throwable) { null } ?: return null
        val intent = act.intent ?: return null
        return intent.getStringExtra("android.provider.extra.APP_PACKAGE")
            ?: intent.getStringExtra("package")
            ?: intent.getStringExtra("packageName")
            ?: intent.getStringExtra("app_package")
            ?: intent.data?.let { uri ->
                if (uri.scheme == "package") uri.schemeSpecificPart else null
            }
    }

    private fun extractChannelIdFromParentActivity(fragment: Any): String? {
        val act = try {
            XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
        } catch (_: Throwable) { null } ?: return null
        val intent = act.intent ?: return null
        return intent.getStringExtra("android.provider.extra.CHANNEL_ID")
            ?: intent.getStringExtra("channel_id")
            ?: intent.getStringExtra("channelId")
            ?: intent.getStringExtra("channel")
    }

    @Suppress("DEPRECATION")
    private fun dumpFragmentForDebug(fragment: Any) {
        val sb = StringBuilder()
        sb.appendLine("--- FRAGMENT FIELD DUMP ---")
        var c: Class<*>? = fragment.javaClass
        while (c != null && c.name != "java.lang.Object") {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(fragment)
                    val vStr = when {
                        v == null -> "null"
                        v is String -> "\"$v\""
                        v is Number || v is Boolean -> v.toString()
                        v is android.app.NotificationChannel -> "NotificationChannel(id=${v.id}, name=${v.name})"
                        else -> "${v.javaClass.name}@${System.identityHashCode(v).toString(16)}"
                    }
                    sb.appendLine("  ${c.simpleName}.${f.name}: ${f.type.simpleName} = $vStr")
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        sb.appendLine("--- ARGUMENTS ---")
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
            if (args != null) {
                for (k in args.keySet()) {
                    val v = args.get(k)
                    val vStr = when (v) {
                        is String -> "\"$v\""
                        is Bundle -> "Bundle(keys=${v.keySet()})"
                        is android.app.NotificationChannel -> "NotificationChannel(id=${v.id}, name=${v.name})"
                        else -> "$v"
                    }
                    sb.appendLine("  arg[$k] = $vStr (${v?.javaClass?.simpleName})")
                }
            } else sb.appendLine("  (null)")
        } catch (_: Throwable) { sb.appendLine("  (error)") }
        sb.appendLine("--- ACTIVITY INTENT ---")
        try {
            val act = XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
            val intent = act?.intent
            if (intent?.extras != null) {
                for (k in intent.extras!!.keySet()) {
                    val v = intent.extras!!.get(k)
                    val vStr = when (v) {
                        is String -> "\"$v\""
                        is Bundle -> "Bundle(keys=${v.keySet()})"
                        is android.app.NotificationChannel -> "NotificationChannel(id=${v.id}, name=${v.name})"
                        else -> "$v"
                    }
                    sb.appendLine("  intent[$k] = $vStr (${v?.javaClass?.simpleName})")
                }
            } else sb.appendLine("  (null/no extras)")
        } catch (_: Throwable) { sb.appendLine("  (error)") }
        for (line in sb.toString().lines()) {
            if (line.isNotBlank()) XposedBridge.log("$TAG $line")
        }
    }

    private fun shouldLogChannelIconDiag(className: String): Boolean =
        className.contains("Channel", ignoreCase = true) &&
            className.contains("Notification", ignoreCase = true) ||
            className.contains("oplus.notificationmanager", ignoreCase = true)

    private fun shouldDeferOplusChannelIdRetry(className: String): Boolean {
        val n = className
        if (n.contains("ChannelNotificationSettingsFragment", ignoreCase = true)) return true
        if (n.contains("ConversationNotificationSettings", ignoreCase = true)) return true
        if (n.startsWith("com.oplus.notificationmanager", ignoreCase = true)) {
            if (n.contains("AppNotification", ignoreCase = true)) return false
            return n.contains("channel", ignoreCase = true) &&
                n.contains("Notification", ignoreCase = true)
        }
        if (n.contains("oneplus.notificationmanager", ignoreCase = true) &&
            n.contains("channel", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun scheduleOplusChannelIdRetries(fragment: Any, knownChannelActivity: Boolean = false) {
        if (!oplusChannelRetryScheduled.add(fragment)) return
        val h = Handler(Looper.getMainLooper())
        val delays = longArrayOf(120L, 300L, 700L, 1500L, 3000L, 5000L)
        for (d in delays) {
            h.postDelayed({
                try {
                    maybeInject(fragment, fromRetry = true, knownChannelActivity = knownChannelActivity)
                    syncVisibility(fragment)
                } catch (_: Throwable) {}
            }, d)
        }
        h.postDelayed({
            try {
                val cn = fragment.javaClass.name
                val pkg = extractPackageName(fragment)
                val ch = extractChannelId(fragment)
                if (!pkg.isNullOrEmpty() && ch.isNullOrEmpty()) {
                    XposedBridge.log("$TAG channelIcon: channelId still null after retries class=$cn pkg=$pkg — dumping fields")
                    dumpFragmentForDebug(fragment)
                }
            } catch (_: Throwable) {}
        }, 5600L)
    }

    /**
     * Экран «все уведомления приложения» / список каналов — тот же текст переключателей, другой фрагмент.
     * У OEM на экране одного канала часто те же ключи preferences, что и в сводке — смотрим fragment/args.
     */
    private fun isAppLevelNotificationScreen(className: String, screen: Any?, fragment: Any?): Boolean {
        if (fragment != null) {
            if (fragmentHasBoundChannel(fragment)) return false
            if (argumentsImplyPerChannelScreen(fragment)) return false
            val pkgEarly = extractPackageName(fragment)
            val chEarly = extractChannelId(fragment)
            if (!pkgEarly.isNullOrEmpty() && !chEarly.isNullOrEmpty()) return false
        }
        val cn = className.lowercase()
        if (cn.contains("channelnotificationsettings") ||
            cn.contains("notificationchannelsettings") ||
            cn.contains("singlechannelnotification")
        ) {
            return false
        }
        if (cn.contains("appnotification") && !cn.contains("channelnotification")) {
            return true
        }
        for (m in APP_LEVEL_EXTRA_MARKERS) {
            if (cn.contains(m.lowercase())) {
                if (cn.contains("channelnotification") || cn.contains("notificationchannel")) return false
                return true
            }
        }
        if (screen != null && screenLooksLikeAppNotificationSummary(screen)) {
            return true
        }
        return false
    }

    /**
     * В аргументах фрагмента/Intent явно передан канал (типичный сценарий Oplus до отражения mChannel).
     */
    private fun argumentsImplyPerChannelScreen(fragment: Any): Boolean {
        val args = try {
            XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
        } catch (_: Throwable) {
            null
        } ?: return false
        val channelKeysStrict = listOf(
            "channel_id",
            "channelId",
            "android.intent.extra.CHANNEL_ID",
            "android.provider.extra.CHANNEL_ID",
            "android.provider.EXTRA_CHANNEL_ID",
            "channel"
        )
        for (k in channelKeysStrict) {
            val v = args.getString(k)
            if (!v.isNullOrEmpty() && !isBadChannelIdPlaceholder(v)) return true
        }
        val nestedKeys = listOf(
            ":settings:show_fragment_args",
            "android:show_fragment_args",
            "show_fragment_args"
        )
        for (nk in nestedKeys) {
            val child = try {
                args.getBundle(nk)
            } catch (_: Throwable) {
                null
            } ?: continue
            for (k in channelKeysStrict) {
                val v = child.getString(k)
                if (!v.isNullOrEmpty() && !isBadChannelIdPlaceholder(v)) return true
            }
        }
        return false
    }

    private fun screenLooksLikeAppNotificationSummary(screen: Any): Boolean {
        for (k in APP_LEVEL_SCREEN_KEYS) {
            if (PreferenceHelper.findByKey(screen, k) != null) return true
        }
        return false
    }

    /**
     * Разрешаем инжек только если похоже на экран ОДНОГО канала (AOSP / Oplus / поле mChannel).
     */
    private fun isAllowedSingleChannelScreen(
        className: String,
        fragment: Any,
        screen: Any?,
        channelId: String
    ): Boolean {
        val cn = className.lowercase()
        if (cn.contains("channelnotificationsettings") ||
            cn.contains("notificationchannelsettings") ||
            cn.contains("channelnotificationfragment") ||
            cn.contains("singlechannelnotification")
        ) {
            return true
        }
        if (fragmentHasBoundChannel(fragment)) return true
        if (argumentsImplyPerChannelScreen(fragment)) return true
        val pkg = extractPackageName(fragment)
        if (!pkg.isNullOrEmpty() && !channelId.isEmpty() &&
            screen != null && screenLooksLikeAppNotificationSummary(screen)
        ) {
            // Раньше отсекали по ключам на экране; если уже есть id канала и пакет — это деталь канала (OEM дублирует ключи).
            return true
        }
        if (screen != null && !screenLooksLikeAppNotificationSummary(screen)) {
            if (cn.contains("oplus", ignoreCase = true) &&
                cn.contains("channel", ignoreCase = true) &&
                cn.contains("notification", ignoreCase = true) &&
                !cn.contains("appnotification")
            ) {
                return true
            }
        }
        return false
    }

    private fun fragmentHasBoundChannel(fragment: Any): Boolean {
        try {
            val id = XposedHelpers.getObjectField(fragment, "mChannelId") as? String
            if (!id.isNullOrEmpty()) return true
        } catch (_: Throwable) {}
        try {
            val ch = XposedHelpers.getObjectField(fragment, "mChannel")
                ?: XposedHelpers.getObjectField(fragment, "mNotificationChannel")
            if (ch != null && (XposedHelpers.callMethod(ch, "getId") as? String)?.isNotEmpty() == true) {
                return true
            }
        } catch (_: Throwable) {}
        var c: Class<*>? = fragment.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type == NotificationChannel::class.java) {
                    try {
                        f.isAccessible = true
                        val ch = f.get(fragment) as? NotificationChannel
                        if (ch?.id?.isNotEmpty() == true) return true
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }
        return false
    }

    private fun injectInternal(fragment: Any, pkg: String, channelId: String) {
        val context = try { XposedHelpers.callMethod(fragment, "getContext") as? Context } catch (_: Throwable) { null } ?: return
        val classLoader = fragment.javaClass.classLoader ?: return
        val screen = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null }
        if (screen == null) {
            XposedBridge.log("$TAG channelIcon: abort getPreferenceScreen=null ${fragment.javaClass.name}")
            return
        }
        if (PreferenceHelper.findByKey(screen, PREF_KEY_ICON_ENABLED) != null) return

        val moduleCtx = try {
            context.createPackageContext(PreferenceUtils.MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Throwable) { context }

        // ── Load current state from disk ──────────────────────────────
        val map = try { ChannelIconJsonWriter.readMapFromDisk() } catch (_: Throwable) { mutableMapOf() }
        val storeKey = ChannelIconStore.storeKey(pkg, channelId)
        val currentPathHolder      = arrayOf(map[storeKey] ?: "")
        val currentShadePathHolder = arrayOf(map["$storeKey${ChannelIconStore.SHADE_SUFFIX}"] ?: "")
        val currentAodPathHolder   = arrayOf(map["$storeKey${ChannelIconStore.AOD_SUFFIX}"]   ?: "")
        val isMonoHolder = arrayOf(map["$storeKey${ChannelIconStore.MONO_SUFFIX}"] == "true")
        val isEnabledInit = currentPathHolder[0].isNotEmpty() ||
            currentShadePathHolder[0].isNotEmpty() || currentAodPathHolder[0].isNotEmpty()

        // ── Migrate external-storage paths to /data/system/ ──────────
        // Existing icons saved with paths like /storage/emulated/0/... are unreadable by
        // SystemUI and AOD processes. Copy them to /data/system/ once, transparently.
        Thread {
            var migrated = false
            val holders = listOf(
                Triple(currentPathHolder, storeKey, "main"),
                Triple(currentShadePathHolder, "$storeKey${ChannelIconStore.SHADE_SUFFIX}", "shade"),
                Triple(currentAodPathHolder, "$storeKey${ChannelIconStore.AOD_SUFFIX}", "aod")
            )
            for ((holder, key, label) in holders) {
                val oldPath = holder[0]
                if (oldPath.isEmpty()) continue
                if (oldPath.startsWith("/data/system/")) continue  // already system path
                try {
                    val bytes = java.io.File(oldPath).readBytes()
                    val ext = oldPath.substringAfterLast('.', "bin").takeIf { it.length in 1..5 } ?: "bin"
                    val dir = "/data/system/${PreferenceUtils.MODULE_PKG}_icons"
                    val name = "icon_${(oldPath.hashCode() and 0x7FFFFFFF)}.$ext"
                    val destPath = "$dir/$name"
                    val process = ProcessBuilder("su", "-c",
                        "mkdir -p '$dir' && chmod 755 '$dir' && cat > '$destPath' && chmod 644 '$destPath'"
                    ).redirectErrorStream(true).start()
                    process.outputStream.use { it.write(bytes) }
                    if (process.waitFor() == 0) {
                        holder[0] = destPath
                        migrated = true
                        XposedBridge.log("$TAG channelIcon: migrated $label path for $pkg/$channelId")
                    }
                } catch (_: Throwable) {}
            }
            if (migrated) {
                // Persist migrated paths back to disk.
                try {
                    val updatedMap = ChannelIconJsonWriter.readMapFromDisk().toMutableMap()
                    for ((holder, key, _) in holders) {
                        val p = holder[0]
                        if (p.isEmpty()) updatedMap.remove(key) else updatedMap[key] = p
                    }
                    ChannelIconJsonWriter.writeMapToDisk(updatedMap)
                    ChannelIconStore.invalidateCache()
                } catch (_: Throwable) {}
            }
        }.start()

        // ── Find preference classes ───────────────────────────────────
        val prefClass = try { XposedHelpers.findClass("androidx.preference.Preference", classLoader) }
            catch (t: Throwable) { XposedBridge.log("$TAG channelIcon: Preference not found: ${t.message}"); return }

        val swClass: Class<*>? = run {
            for (name in listOf(
                "com.coui.appcompat.preference.COUISwitchPreferenceCompat",
                "com.coui.appcompat.preference.COUISwitchPreference",
                "androidx.preference.SwitchPreferenceCompat",
                "androidx.preference.SwitchPreference"
            )) { try { return@run XposedHelpers.findClass(name, classLoader) } catch (_: Throwable) {} }
            null
        }
        val catClass: Class<*>? = run {
            for (name in listOf(
                "com.coui.appcompat.preference.COUIPreferenceCategory",
                "com.coui.appcompat.preference.COUICategoryPreference",
                "androidx.preference.PreferenceCategory"
            )) { try { return@run XposedHelpers.findClass(name, classLoader) } catch (_: Throwable) {} }
            null
        }
        val changeListenerClass = try {
            XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", classLoader)
        } catch (_: Throwable) { null }
        val clickListenerClass = try {
            XposedHelpers.findClass("androidx.preference.Preference\$OnPreferenceClickListener", classLoader)
        } catch (_: Throwable) { null }

        // ── Strings ───────────────────────────────────────────────────
        fun str(id: Int, fallback: String) = try { moduleCtx.getString(id) } catch (_: Throwable) { fallback }
        val titleEnabled  = str(R.string.channel_icon_enabled, "Custom icon")
        val titleFile     = str(R.string.channel_icon_file, "Main icon")
        val titleShade    = str(R.string.channel_icon_file_shade, "Notification shade icon")
        val titleAod      = str(R.string.channel_icon_file_aod, "AOD icon")
        val hintPath      = str(R.string.channel_icon_path_hint, "Tap to select icon file")
        val titleMono     = str(R.string.pref_shade_group_mono, "Monochrome in shade")
        val comingSoon    = str(R.string.pref_coming_soon, "Coming soon")
        val toastFailed   = str(R.string.channel_icon_save_failed, "Could not save")

        // ── Find existing native PreferenceCategory for style/class matching ──
        val nativeCat: Any? = run {
            val count = try { XposedHelpers.callMethod(screen, "getPreferenceCount") as? Int }
                catch (_: Throwable) { null } ?: return@run null
            for (i in 0 until count) {
                val g = try { XposedHelpers.callMethod(screen, "getPreference", i) } catch (_: Throwable) { null } ?: continue
                val childCount = try { XposedHelpers.callMethod(g, "getPreferenceCount") as? Int } catch (_: Throwable) { 0 } ?: 0
                if (childCount > 0) return@run g
            }
            null
        }
        // Use native category class if available (ensures COUI renders identically)
        val effectiveCatClass: Class<*>? = nativeCat?.javaClass ?: catClass

        // Plain COUI preference class for pathPref (needs setLayoutCategory support)
        val couiPrefClass: Class<*> = run {
            for (name in listOf(
                "com.coui.appcompat.preference.COUIPreference",
                "com.coui.appcompat.preference.COUITwoTargetPreference"
            )) { try { return@run XposedHelpers.findClass(name, classLoader) } catch (_: Throwable) {} }
            // Fallback: use first child of native category — guaranteed COUI class
            val firstNativeChild = nativeCat?.let { cat ->
                try {
                    val cnt = XposedHelpers.callMethod(cat, "getPreferenceCount") as? Int ?: 0
                    if (cnt > 0) XposedHelpers.callMethod(cat, "getPreference", 0) else null
                } catch (_: Throwable) { null }
            }
            firstNativeChild?.javaClass ?: prefClass
        }

        // ── Auto-save helper ──────────────────────────────────────────
        fun autoSave() {
            Thread {
                val newMap = try { ChannelIconJsonWriter.readMapFromDisk() } catch (_: Throwable) { mutableMapOf() }.toMutableMap()
                val path = currentPathHolder[0]
                if (path.isEmpty()) newMap.remove(storeKey) else newMap[storeKey] = path
                val shadePath = currentShadePathHolder[0]
                val shadeKey = "$storeKey${ChannelIconStore.SHADE_SUFFIX}"
                if (shadePath.isEmpty()) newMap.remove(shadeKey) else newMap[shadeKey] = shadePath
                val aodPath = currentAodPathHolder[0]
                val aodKey = "$storeKey${ChannelIconStore.AOD_SUFFIX}"
                if (aodPath.isEmpty()) newMap.remove(aodKey) else newMap[aodKey] = aodPath
                val monoKey = "$storeKey${ChannelIconStore.MONO_SUFFIX}"
                newMap[monoKey] = "true"  // always true (coming soon feature)
                if (!ChannelIconJsonWriter.writeMapToDisk(newMap)) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, toastFailed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    ChannelIconStore.invalidateCache()
                }
            }.start()
        }

        // ── "Custom icon" toggle ──────────────────────────────────────
        val swEnabled: Any = run {
            val sw = if (swClass != null) {
                try { XposedHelpers.newInstance(swClass, context) } catch (_: Throwable) {
                    XposedHelpers.newInstance(prefClass, context)
                }
            } else {
                XposedHelpers.newInstance(prefClass, context)
            }
            XposedHelpers.callMethod(sw, "setKey", PREF_KEY_ICON_ENABLED)
            XposedHelpers.callMethod(sw, "setTitle", titleEnabled)
            XposedHelpers.callMethod(sw, "setPersistent", false)
            try { XposedHelpers.callMethod(sw, "setChecked", isEnabledInit) } catch (_: Throwable) {}
            sw
        }

        // ── "Icon file" picker preference ─────────────────────────────
        val pathPref: Any = run {
            val p = try { XposedHelpers.newInstance(couiPrefClass, context) } catch (_: Throwable) {
                try { XposedHelpers.newInstance(couiPrefClass, context, null as android.util.AttributeSet?) } catch (t: Throwable) {
                    XposedBridge.log("$TAG channelIcon: pathPref create failed: ${t.message}"); return
                }
            }
            XposedHelpers.callMethod(p, "setKey", PREF_KEY_ICON_PATH)
            XposedHelpers.callMethod(p, "setTitle", titleFile)
            XposedHelpers.callMethod(p, "setSummary", currentPathHolder[0].ifEmpty { hintPath })
            XposedHelpers.callMethod(p, "setPersistent", false)
            XposedHelpers.callMethod(p, "setEnabled", isEnabledInit)
            if (currentPathHolder[0].isNotEmpty()) trySetIconPreview(p, currentPathHolder[0], context)
            p
        }

        // ── "Notification shade icon" picker ──────────────────────────
        val shadePref: Any = run {
            val p = try { XposedHelpers.newInstance(couiPrefClass, context) } catch (_: Throwable) {
                try { XposedHelpers.newInstance(couiPrefClass, context, null as android.util.AttributeSet?) } catch (t: Throwable) {
                    XposedBridge.log("$TAG channelIcon: shadePref create failed: ${t.message}"); return
                }
            }
            XposedHelpers.callMethod(p, "setKey", PREF_KEY_ICON_PATH_SHADE)
            XposedHelpers.callMethod(p, "setTitle", titleShade)
            XposedHelpers.callMethod(p, "setSummary", currentShadePathHolder[0].ifEmpty { hintPath })
            XposedHelpers.callMethod(p, "setPersistent", false)
            XposedHelpers.callMethod(p, "setEnabled", isEnabledInit)
            if (currentShadePathHolder[0].isNotEmpty()) trySetIconPreview(p, currentShadePathHolder[0], context)
            p
        }

        // ── "AOD icon" picker ──────────────────────────────────────────
        val aodPref: Any = run {
            val p = try { XposedHelpers.newInstance(couiPrefClass, context) } catch (_: Throwable) {
                try { XposedHelpers.newInstance(couiPrefClass, context, null as android.util.AttributeSet?) } catch (t: Throwable) {
                    XposedBridge.log("$TAG channelIcon: aodPref create failed: ${t.message}"); return
                }
            }
            XposedHelpers.callMethod(p, "setKey", PREF_KEY_ICON_PATH_AOD)
            XposedHelpers.callMethod(p, "setTitle", titleAod)
            XposedHelpers.callMethod(p, "setSummary", currentAodPathHolder[0].ifEmpty { hintPath })
            XposedHelpers.callMethod(p, "setPersistent", false)
            XposedHelpers.callMethod(p, "setEnabled", isEnabledInit)
            if (currentAodPathHolder[0].isNotEmpty()) trySetIconPreview(p, currentAodPathHolder[0], context)
            p
        }

        // ── "Monochrome in shade" switch — coming soon, always on ─────
        val monoPref: Any? = swClass?.let { cls ->
            try {
                val sw = XposedHelpers.newInstance(cls, context)
                XposedHelpers.callMethod(sw, "setKey", PREF_KEY_ICON_MONO)
                XposedHelpers.callMethod(sw, "setTitle", titleMono)
                XposedHelpers.callMethod(sw, "setSummary", comingSoon)
                XposedHelpers.callMethod(sw, "setPersistent", false)
                XposedHelpers.callMethod(sw, "setChecked", true)
                XposedHelpers.callMethod(sw, "setEnabled", false)
                sw
            } catch (t: Throwable) {
                XposedBridge.log("$TAG channelIcon: monoPref error: ${t.message}"); null
            }
        }

        // ── Listeners ─────────────────────────────────────────────────

        // swEnabled: on/off → enable/disable dependent prefs; off = reset
        if (changeListenerClass != null) {
            val l = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(changeListenerClass)) { _, method, args ->
                if (method.name == "onPreferenceChange") {
                    val checked = args?.getOrNull(1) as? Boolean ?: false
                    if (!checked) {
                        currentPathHolder[0] = ""
                        currentShadePathHolder[0] = ""
                        currentAodPathHolder[0] = ""
                        Handler(Looper.getMainLooper()).post {
                            for (p in listOf(pathPref, shadePref, aodPref)) {
                                try { XposedHelpers.callMethod(p, "setSummary", hintPath) } catch (_: Throwable) {}
                                trySetIconPreview(p, "", context)
                                try { XposedHelpers.callMethod(p, "setEnabled", false) } catch (_: Throwable) {}
                            }
                        }
                        autoSave()
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            for (p in listOf(pathPref, shadePref, aodPref)) {
                                try { XposedHelpers.callMethod(p, "setEnabled", true) } catch (_: Throwable) {}
                            }
                        }
                    }
                    true
                } else false
            }
            XposedHelpers.callMethod(swEnabled, "setOnPreferenceChangeListener", l)
        }

        // File picker listeners for pathPref, shadePref, aodPref
        if (clickListenerClass != null) {
            fun makePickerListener(pathHolder: Array<String>, displayPref: Any): Any =
                java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(clickListenerClass)) { _, method, _ ->
                    if (method.name == "onPreferenceClick") {
                        val activity = try {
                            XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
                        } catch (_: Throwable) { null }
                        if (activity != null) {
                            pendingFilePickerCallback = { path ->
                                pathHolder[0] = path
                                Handler(Looper.getMainLooper()).post {
                                    try { XposedHelpers.callMethod(displayPref, "setSummary", path.ifEmpty { hintPath }) } catch (_: Throwable) {}
                                    trySetIconPreview(displayPref, path, context)
                                    if (path.isNotEmpty()) {
                                        try { XposedHelpers.callMethod(swEnabled, "setChecked", true) } catch (_: Throwable) {}
                                    }
                                }
                                autoSave()
                            }
                            try {
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }
                                activity.startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
                            } catch (t: Throwable) {
                                pendingFilePickerCallback = null
                                XposedBridge.log("$TAG channelIcon: startActivityForResult failed: ${t.message}")
                            }
                        }
                        true
                    } else false
                }
            XposedHelpers.callMethod(pathPref,  "setOnPreferenceClickListener", makePickerListener(currentPathHolder,      pathPref))
            XposedHelpers.callMethod(shadePref, "setOnPreferenceClickListener", makePickerListener(currentShadePathHolder, shadePref))
            XposedHelpers.callMethod(aodPref,   "setOnPreferenceClickListener", makePickerListener(currentAodPathHolder,   aodPref))
        }

        // ── Assemble ──────────────────────────────────────────────────
        // COUIBottomPreference provides nav-bar bottom padding — keep it as the last item
        // (full height = correct) and insert our section just before it.
        val bottomPref: Any? = run {
            val cnt = try { XposedHelpers.callMethod(screen, "getPreferenceCount") as? Int ?: 0 } catch (_: Throwable) { 0 }
            for (i in 0 until cnt) {
                val p = try { XposedHelpers.callMethod(screen, "getPreference", i) } catch (_: Throwable) { null } ?: continue
                if (p.javaClass.simpleName.contains("Bottom", ignoreCase = true)) return@run p
            }
            null
        }
        val bottomOrder = bottomPref?.let { try { XposedHelpers.callMethod(it, "getOrder") as? Int } catch (_: Throwable) { null } }
        val baseOrder = if (bottomOrder != null) {
            // Place our section at bottomOrder, push COUIBottomPreference one step further.
            // It remains last → renders with full nav-bar height (same as every native screen).
            try { XposedHelpers.callMethod(bottomPref, "setOrder", bottomOrder + 1) } catch (_: Throwable) {}
            bottomOrder
        } else {
            maxPreferenceOrderInGroup(screen) + 1
        }
        val items = listOfNotNull(swEnabled, pathPref, shadePref, aodPref, monoPref)

        // Use COUIPreferenceCategory as container — COUIPreference items only render
        // setLayoutCategory(1/2/3) correctly when managed by a COUIPreferenceCategory.
        var injected = false
        effectiveCatClass?.let { cls ->
            try {
                val cat = try { XposedHelpers.newInstance(cls, context) }
                    catch (_: Throwable) {
                        try { XposedHelpers.newInstance(cls, context, null as android.util.AttributeSet?) }
                        catch (_: Throwable) { XposedHelpers.newInstance(cls, context, null as android.util.AttributeSet?, 0) }
                    }

                try { XposedHelpers.callMethod(cat, "setOrder", baseOrder) } catch (_: Throwable) {}
                XposedHelpers.callMethod(screen, "addPreference", cat)
                for (p in items) {
                    try { XposedHelpers.callMethod(cat, "addPreference", p) } catch (t: Throwable) {
                        XposedBridge.log("$TAG channelIcon: cat.addPref failed: ${t.message}")
                    }
                }
                injected = true
                XposedBridge.log("$TAG channelIcon: injected via ${cls.simpleName} for $pkg / $channelId")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG channelIcon: category failed (${t.message}), direct fallback")
            }
        }
        if (!injected) {
            items.forEachIndexed { i, p ->
                val catType = when { items.size == 1 -> 0; i == 0 -> 1; i == items.lastIndex -> 3; else -> 2 }
                try { XposedHelpers.callMethod(p, "setLayoutCategory", catType) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(p, "setNeedChangeDrawType", true) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(p, "setShowDivider", i != items.lastIndex) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(p, "setOrder", baseOrder + i) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(screen, "addPreference", p) } catch (t: Throwable) {
                    XposedBridge.log("$TAG channelIcon: addPreference[$i] failed: ${t.message}")
                }
            }
            XposedBridge.log("$TAG channelIcon: injected ${items.size} items directly for $pkg / $channelId")
        }

        syncVisibility(fragment)
        fixSectionTopMargin(fragment)

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                val s2 = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null }
                if (s2 != null && PreferenceHelper.findByKey(s2, PREF_KEY_ICON_ENABLED) == null) {
                    XposedBridge.log("$TAG channelIcon: section LOST after 500ms — re-injecting…")
                    injectInternal(fragment, pkg, channelId)
                }
            } catch (t: Throwable) { XposedBridge.log("$TAG channelIcon delayed check: ${t.message}") }
        }, 500)
        handler.postDelayed({
            try {
                val s3 = try { XposedHelpers.callMethod(fragment, "getPreferenceScreen") } catch (_: Throwable) { null }
                if (s3 != null && PreferenceHelper.findByKey(s3, PREF_KEY_ICON_ENABLED) == null) {
                    XposedBridge.log("$TAG channelIcon: section LOST after 1500ms — re-injecting…")
                    injectInternal(fragment, pkg, channelId)
                }
            } catch (t: Throwable) { XposedBridge.log("$TAG channelIcon delayed check: ${t.message}") }
        }, 1500)
    }

    /**
     * After dynamic injection, OplusHighlightablePreferenceGroupAdapter adds 2× the normal
     * inter-section gap before our first item via ItemDecoration.getItemOffsets (topMargin=0
     * so LayoutParams can't be read directly). We counteract by measuring the native gap from
     * nearby items and applying a negative topMargin equal to the excess.
     */
    private fun fixSectionTopMargin(fragment: Any) {
        val fragView = try { XposedHelpers.callMethod(fragment, "getView") as? android.view.View }
            catch (_: Throwable) { null } ?: return
        val rv = findRvInView(fragView) ?: return
        rv.post {
            try {
                val adapter = try { XposedHelpers.callMethod(rv, "getAdapter") }
                    catch (_: Throwable) { null } ?: return@post
                val cnt = (try { XposedHelpers.callMethod(adapter, "getItemCount") }
                    catch (_: Throwable) { null } as? Int) ?: return@post

                // Find adapter position of our COUIPreferenceCategory container.
                // The adapter flattens the hierarchy: category item appears before its children.
                // PREF_KEY_ICON_ENABLED is the first child (inside the card), so the category
                // itself is somewhere before it. Walk backwards to find the category item.
                var childPos = -1
                for (i in 0 until cnt) {
                    val pref = try { XposedHelpers.callMethod(adapter, "getItem", i) }
                        catch (_: Throwable) { null } ?: continue
                    val key = try { XposedHelpers.callMethod(pref, "getKey") as? String }
                        catch (_: Throwable) { null }
                    if (key == PREF_KEY_ICON_ENABLED) { childPos = i; break }
                }
                if (childPos < 1) return@post

                fun getItemView(pos: Int): android.view.View? {
                    val vh = try { XposedHelpers.callMethod(rv, "findViewHolderForAdapterPosition", pos) }
                        catch (_: Throwable) { null } ?: return null
                    return try { XposedHelpers.getObjectField(vh, "itemView") as? android.view.View }
                        catch (_: Throwable) { null }
                }

                // Walk backwards from childPos to find the category container item
                // (class name contains "Category" and key is null)
                var catPos = -1
                for (i in childPos - 1 downTo maxOf(0, childPos - 3)) {
                    val pref = try { XposedHelpers.callMethod(adapter, "getItem", i) }
                        catch (_: Throwable) { null } ?: continue
                    val cls = pref.javaClass.name
                    val key = try { XposedHelpers.callMethod(pref, "getKey") as? String }
                        catch (_: Throwable) { null }
                    XposedBridge.log("$TAG fixGap scan i=$i cls=${pref.javaClass.simpleName} key=$key")
                    if (cls.contains("Category", ignoreCase = true) && key.isNullOrEmpty()) {
                        catPos = i; break
                    }
                }
                // Fallback: if no category item found, the section might start directly at childPos
                val targetPos = if (catPos >= 1) catPos else childPos
                if (targetPos < 1) return@post

                val ourView = getItemView(targetPos) ?: return@post
                val prevView = getItemView(targetPos - 1) ?: return@post

                // Gap currently applied before our section
                val ourGap = ourView.top - prevView.bottom

                // Find the native inter-section gap: search backwards for the first positive gap
                var nativeGap = 0
                for (ref in targetPos - 1 downTo 1) {
                    val v1 = getItemView(ref - 1) ?: continue
                    val v2 = getItemView(ref) ?: continue
                    val g = v2.top - v1.bottom
                    if (g > 0) { nativeGap = g; break }
                }

                XposedBridge.log("$TAG fixGap catPos=$catPos childPos=$childPos targetPos=$targetPos ourGap=$ourGap nativeGap=$nativeGap")

                // Fix 1: excess gap before category header itself
                if (nativeGap > 0 && ourGap > nativeGap) {
                    val excess = ourGap - nativeGap
                    val lp = ourView.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return@post
                    lp.topMargin = -excess
                    ourView.layoutParams = lp
                    ourView.requestLayout()
                    XposedBridge.log("$TAG fixGap fix1 excess=-$excess to pos=$targetPos")
                }

                // Fix 2: detect "double category header" situation.
                // The adapter sometimes inserts an extra zero-height COUIPreferenceCategory
                // before ours (e.g. a native placeholder or auto-generated spacer), causing
                // a double 53px gap (pos=11 h=0, pos=12=ours h=0, each with 53px decoration).
                // Fix: collapse our category (pos=12) onto the preceding one (pos=11) by
                // applying a negative topMargin equal to the gap between them.
                if (catPos >= 1) {
                    val prevPref = try { XposedHelpers.callMethod(adapter, "getItem", catPos - 1) }
                        catch (_: Throwable) { null }
                    val prevCls = prevPref?.javaClass?.name ?: ""
                    val prevKey = prevPref?.let {
                        try { XposedHelpers.callMethod(it, "getKey") as? String } catch (_: Throwable) { null }
                    }
                    val prevView2 = getItemView(catPos - 1)
                    XposedBridge.log("$TAG fixGap prevOfCat pos=${catPos-1} cls=${prevPref?.javaClass?.simpleName} key=$prevKey h=${prevView2?.height}")
                    if (prevCls.contains("Category", ignoreCase = true) &&
                        prevKey.isNullOrEmpty() &&
                        prevView2 != null && prevView2.height == 0
                    ) {
                        // The item immediately before our category is also a zero-height
                        // category with no key → extra spacer. The 53px gap between them
                        // comes from pos=11's bottomDecoration (not pos=12's topDecoration).
                        // Fix: apply negative bottomMargin to pos=11 to cancel its bottomDecoration.
                        val extraGap = ourView.top - prevView2.bottom
                        XposedBridge.log("$TAG fixGap fix2 extraGap=$extraGap between pos=${catPos-1} and catPos=$catPos")
                        if (extraGap > 0) {
                            val lp = prevView2.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                            if (lp != null) {
                                lp.bottomMargin = -extraGap
                                prevView2.layoutParams = lp
                                prevView2.requestLayout()
                                XposedBridge.log("$TAG fixGap fix2 applied bottomMargin=-$extraGap to pos=${catPos-1}")
                            }
                        }
                    }
                }
            } catch (t: Throwable) { XposedBridge.log("$TAG fixGap: ${t.message}") }
        }
    }

    private fun findRvInView(v: android.view.View?): android.view.View? {
        if (v == null) return null
        if (v.javaClass.name.contains("RecyclerView")) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findRvInView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun maxPreferenceOrderInGroup(group: Any): Int {
        val prefCount = try {
            XposedHelpers.callMethod(group, "getPreferenceCount") as? Int
        } catch (_: Throwable) {
            null
        } ?: return 0
        var maxO = 0
        for (i in 0 until prefCount) {
            val p = try { XposedHelpers.callMethod(group, "getPreference", i) } catch (_: Throwable) { null } ?: continue
            val o = try { XposedHelpers.callMethod(p, "getOrder") as? Int } catch (_: Throwable) { null } ?: 0
            maxO = max(maxO, o)
        }
        return maxO
    }

    /** Типичное имя пакета Android — есть хотя бы одна точка. */
    private fun looksLikeAndroidPackageName(s: String): Boolean =
        !s.contains(' ') && (s.contains('.') && s.length > 3 || s == "android")

    /**
     * У Oplus в [mChannelId] иногда лежит подпись канала («AlfaBank»), а реальный id — в [NotificationChannel].
     */
    private fun isLikelyOemChannelLabelNotId(s: String): Boolean {
        if (s.contains('.') || s.contains('_') || s.contains('-')) return false
        if (s.length >= 36) return false
        if (s.any { it.isDigit() }) return false
        if (s.contains(' ')) return false
        if (s.all { it.isLetter() } && s.any { it.isUpperCase() } && s.length <= 40) return true
        return false
    }

    /** Любой String extra, в ключе есть package/pkg. */
    private fun extractPackageNameFromBundleKeys(bundle: Bundle?): String? {
        if (bundle == null) return null
        return try {
            for (key in bundle.keySet()) {
                if (!key.contains("package", ignoreCase = true) &&
                    !key.contains("pkg", ignoreCase = true)
                ) {
                    continue
                }
                val v = bundle.getString(key)
                if (!v.isNullOrEmpty() && looksLikeAndroidPackageName(v)) return v
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun scanFragmentStringFieldsForPackage(fragment: Any): String? {
        var c: Class<*>? = fragment.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java) continue
                val n = f.name.lowercase()
                if (!n.contains("package") && !n.contains("pkg")) continue
                if (n.contains("title") || n.contains("label") || n.contains("summary")) continue
                try {
                    f.isAccessible = true
                    val v = f.get(fragment) as? String
                    if (!v.isNullOrEmpty() && looksLikeAndroidPackageName(v)) return v
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    private fun extractPackageName(fragment: Any, depth: Int = 0): String? {
        if (depth > 5) return null
        for (field in listOf("mPackageName", "mAppPackage", "mTargetPackageName", "mPkg", "mPackage")) {
            try {
                val v = XposedHelpers.getObjectField(fragment, field) as? String
                if (!v.isNullOrEmpty() && looksLikeAndroidPackageName(v)) return v
            } catch (_: Throwable) {}
        }
        scanFragmentStringFieldsForPackage(fragment)?.let { return it }
        readAllBundles(fragment) { args ->
            listOf(
                "package",
                "packageName",
                "pkg",
                "app_package",
                "appPackage",
                "package_name",
                "target_package",
                "android.intent.extra.PACKAGE_NAME",
                "android.provider.extra.PACKAGE_NAME",
                "android.provider.extra.APP_PACKAGE",
                "app_package_name",
                "pkg_name"
            ).firstNotNullOfOrNull { k ->
                args.getString(k)?.takeIf { it.isNotEmpty() && looksLikeAndroidPackageName(it) }
            }
        }?.let { return it }
        try {
            val args = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
            extractPackageNameFromBundleKeys(args)?.let { return it }
        } catch (_: Throwable) {}
        try {
            val act = XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
            extractPackageNameFromBundleKeys(act?.intent?.extras)?.let { return it }
        } catch (_: Throwable) {}
        try {
            XposedHelpers.getObjectField(fragment, "mPkg") as? String
        } catch (_: Throwable) {
            null
        }?.takeIf { looksLikeAndroidPackageName(it) }?.let { return it }
        try {
            val act = XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
            val data = act?.intent?.data
            if (data != null && data.scheme == "package") {
                val p = data.schemeSpecificPart?.trim()
                if (!p.isNullOrEmpty() && looksLikeAndroidPackageName(p)) return p
            }
        } catch (_: Throwable) {}
        try {
            val parent = XposedHelpers.callMethod(fragment, "getParentFragment")
            if (parent != null) {
                extractPackageName(parent, depth + 1)?.let { return it }
            }
        } catch (_: Throwable) {}
        return try {
            XposedHelpers.getObjectField(fragment, "mPackage") as? String
        } catch (_: Throwable) {
            null
        }?.takeIf { looksLikeAndroidPackageName(it) }
    }

    /**
     * Порядок важен: у Oplus в ключе `channel` часто лежит **название** канала («AlfaBank»), а не id.
     * Сначала объект [NotificationChannel] и строгие ключи, строку `channel` — в конце.
     */
    private fun extractChannelId(fragment: Any): String? {
        extractChannelIdFromFragmentFieldsFirst(fragment)?.let { return it }

        readAllBundles(fragment) { args -> notificationChannelIdFromParcelable(args) }?.let { return it }

        val trustedKeys = setOf(
            "android.intent.extra.CHANNEL_ID",
            "android.provider.extra.CHANNEL_ID",
            "android.provider.EXTRA_CHANNEL_ID",
            "channel_id",
            "channelId"
        )
        val ambiguousKeys = listOf(
            "mChannelId",
            "com.oplus.notificationmanager.extra.CHANNEL_ID",
            "oplus.extra.CHANNEL_ID",
            "EXTRA_CHANNEL_ID"
        )
        readAllBundles(fragment) { args ->
            trustedKeys.firstNotNullOfOrNull { k ->
                args.getString(k)?.takeIf { it.isNotEmpty() && !isBadChannelIdPlaceholder(it) }
            }
        }?.let { return it }

        readAllBundles(fragment) { args ->
            ambiguousKeys.firstNotNullOfOrNull { k ->
                args.getString(k)?.takeIf {
                    it.isNotEmpty() &&
                        !isBadChannelIdPlaceholder(it) &&
                        !isLikelyOemChannelLabelNotId(it)
                }
            }
        }?.let { return it }

        readAllBundles(fragment) { args ->
            args.getString("channel")?.takeIf {
                it.isNotEmpty() &&
                    !isBadChannelIdPlaceholder(it) &&
                    !isLikelyOemChannelLabelNotId(it)
            }
        }?.let { return it }

        readAllBundles(fragment) { args ->
            (ambiguousKeys + "channel").firstNotNullOfOrNull { k ->
                args.getString(k)?.takeIf { it.isNotEmpty() && !isBadChannelIdPlaceholder(it) }
            }
        }?.let { return it }

        return null
    }

    private fun extractChannelIdFromFragmentFieldsFirst(fragment: Any): String? {
        for (method in listOf("getChannel", "getNotificationChannel", "getChannelInfo")) {
            try {
                val r = XposedHelpers.callMethod(fragment, method) ?: continue
                when (r) {
                    is NotificationChannel -> r.id.takeIf { it.isNotEmpty() }?.let { return it }
                    else -> {
                        (XposedHelpers.callMethod(r, "getId") as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
            } catch (_: Throwable) {}
        }
        try {
            val ch = XposedHelpers.getObjectField(fragment, "mChannel")
                ?: XposedHelpers.getObjectField(fragment, "mNotificationChannel")
            if (ch != null) {
                (XposedHelpers.callMethod(ch, "getId") as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (_: Throwable) {}

        extractChannelIdFromFragmentNotificationChannelFields(fragment)?.let { return it }

        try {
            val sid = XposedHelpers.getObjectField(fragment, "mChannelId") as? String
            if (!sid.isNullOrEmpty() && !isBadChannelIdPlaceholder(sid) && !isLikelyOemChannelLabelNotId(sid)) {
                return sid
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun extractChannelIdFromFragmentNotificationChannelFields(fragment: Any): String? {
        var c: Class<*>? = fragment.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type == NotificationChannel::class.java) {
                    try {
                        f.isAccessible = true
                        val ch = f.get(fragment) as? NotificationChannel
                        val id = ch?.id
                        if (!id.isNullOrEmpty()) return id
                    } catch (_: Throwable) {}
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun notificationChannelIdFromParcelable(args: Bundle): String? {
        val keys = listOf(
            "android.provider.extra.CHANNEL",
            "channel",
            "notification_channel",
            "android.app.extra.NOTIFICATION_CHANNEL"
        )
        for (k in keys) {
            val id = getNotificationChannelIdFromBundle(args, k)
            if (!id.isNullOrEmpty()) return id
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getNotificationChannelIdFromBundle(args: Bundle, key: String): String? {
        return try {
            val ch: NotificationChannel? = if (Build.VERSION.SDK_INT >= 33) {
                args.getParcelable(key, NotificationChannel::class.java)
            } else {
                args.getParcelable(key) as? NotificationChannel
            }
            ch?.id?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            try {
                val p = args.getParcelable(key) as? Parcelable
                if (p != null) {
                    XposedHelpers.callMethod(p, "getId") as? String
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun isBadChannelIdPlaceholder(id: String): Boolean {
        val s = id.lowercase()
        if (CHANNEL_ID_EXCLUDE_PREFIXES.any { s.startsWith(it) }) return true
        return false
    }

    /**
     * Merges fragment arguments, nested Settings bundles, and activity intent extras.
     */
    private fun readAllBundles(fragment: Any, pick: (Bundle) -> String?): String? {
        val args = try {
            XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
        } catch (_: Throwable) {
            null
        }
        args?.let { b ->
            pick(b)?.let { return it }
            walkNestedBundles(b, pick)?.let { return it }
        }
        val act = try {
            XposedHelpers.callMethod(fragment, "getActivity") as? android.app.Activity
        } catch (_: Throwable) {
            null
        }
        val intent = act?.intent
        intent?.extras?.let { b ->
            pick(b)?.let { return it }
            walkNestedBundles(b, pick)?.let { return it }
        }
        return null
    }

    private fun walkNestedBundles(root: Bundle, pick: (Bundle) -> String?): String? {
        val nestedKeys = listOf(
            ":settings:show_fragment_args",
            "android:show_fragment_args",
            "show_fragment_args",
            "preference_args"
        )
        for (nk in nestedKeys) {
            val child = try {
                root.getBundle(nk)
            } catch (_: Throwable) {
                null
            } ?: continue
            val v = pick(child)
            if (v != null) return v
        }
        return null
    }

    private fun isChannelNotificationsAllowed(context: Context, pkg: String, channelId: String): Boolean {
        return try {
            val appCtx = try {
                context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Throwable) {
                context
            }
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch: NotificationChannel? = nm.getNotificationChannel(channelId)
            if (ch == null) return true
            ch.importance != NotificationManager.IMPORTANCE_NONE
        } catch (_: Throwable) {
            true
        }
    }
}
