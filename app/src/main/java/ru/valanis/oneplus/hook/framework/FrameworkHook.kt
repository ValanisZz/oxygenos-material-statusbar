package ru.valanis.oneplus.hook.framework

import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ru.valanis.oneplus.hook.core.IconUtils
import ru.valanis.oneplus.hook.core.PreferenceUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("PrivateApi")
object FrameworkHook {

    private const val TAG = "OxygenOSMaterialIcons"

    // Even a stream of identical updates is let through this often, as a safety net
    private const val FORCE_PASS_MS = 60_000L
    private const val MAX_TRACKED = 120

    private class DedupEntry(@Volatile var hash: Int, @Volatile var lastPassMs: Long)

    private val dedupEntries = ConcurrentHashMap<String, DedupEntry>()
    private val droppedTotal = AtomicLong(0)

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val helperClass = XposedHelpers.findClass(
                "com.android.server.notification.OplusNotificationFixHelper",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                helperClass,
                "fixSmallIcon",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PreferenceUtils.isModuleEnabled()) return
                        val notif = param.args.getOrNull(0) as? Notification ?: return
                        try {
                            val orig = notif.smallIcon
                            if (orig != null) {
                                notif.extras.putParcelable(IconUtils.EXTRAS_ORIGINAL_ICON, orig)
                            }
                        } catch (_: Throwable) {}
                        if (PreferenceUtils.isStatusBarEnabled()) {
                            param.result = notif
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Framework hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Framework: ${t.message}")
        }
        try {
            hookNotificationDedup(lpparam)
            XposedBridge.log("$TAG: Framework dedup hook OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Framework dedup: ${t.message}")
        }
    }

    /**
     * Drops updates of ongoing/foreground-service notifications whose visible content
     * is identical to the previous post of the same (pkg, tag, id, user). Apps like CGM
     * monitors repost the same notification in bursts; each repost wakes system_server
     * ranking, re-inflates RemoteViews in SystemUI (collapsed + expanded) and pings the
     * AOD listener. Anything that could convey new information always passes:
     *   - non-ongoing notifications (alerts) are never touched,
     *   - custom RemoteViews and big pictures can't be compared, so they always pass,
     *   - any change in visible fields changes the hash, so the update passes,
     *   - identical updates still pass at least once per [FORCE_PASS_MS].
     */
    // Diagnostics: logs every evaluation, skip reason, pass and drop to the LSPosed log
    private const val DEBUG_DEDUP = false

    private fun hookNotificationDedup(lpparam: XC_LoadPackage.LoadPackageParam) {
        val nmsClass = XposedHelpers.findClass(
            "com.android.server.notification.NotificationManagerService",
            lpparam.classLoader
        )
        // enqueueNotificationWithTag lives in an anonymous Binder stub, not in NMS itself —
        // enqueueNotificationInternal is declared on the NMS class proper.
        // enqueueNotificationInternal overloads delegate to one another with the SAME
        // Notification instance. Only the outermost call may decide: a passed update
        // refreshes the stored hash, so the nested self-call would see "identical
        // content" and wrongly drop the update (froze e.g. Chrome download progress).
        // Nested calls are detected by Notification object identity with a short TTL.
        // No after-hook cleanup: it proved unreliable on this firmware, and the AMS
        // foreground-service path re-posts the SAME Notification instance minutes apart,
        // which must be evaluated again. Delegation happens within the same call stack
        // (microseconds), so a 1 s TTL cleanly separates the two cases; a stale marker
        // merely skips evaluation (pass-through), never drops.
        val processingMarker = ThreadLocal<Pair<Notification, Long>?>()
        val unhooks = XposedBridge.hookAllMethods(nmsClass, "enqueueNotificationInternal", object : XC_MethodHook() {
            @Suppress("DEPRECATION")
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    fun debugSkip(reason: String) {
                        if (!DEBUG_DEDUP) return
                        val pkg0 = param.args.getOrNull(0) as? String ?: "?"
                        if (!pkg0.contains("medtronic")) return
                        XposedBridge.log(
                            "$TAG NotifDedup SKIP($reason) pkg=$pkg0 " +
                                "method=${param.method.name}/${(param.method as? java.lang.reflect.Method)?.parameterCount} " +
                                "thread=${Thread.currentThread().name}"
                        )
                    }
                    if (!PreferenceUtils.isModuleEnabled()) { debugSkip("disabled"); return }
                    val notifIdx = param.args.indexOfFirst { it is Notification }
                    if (notifIdx < 2) { debugSkip("notifIdx=$notifIdx"); return }
                    val n = param.args[notifIdx] as Notification
                    val nowMs = SystemClock.elapsedRealtime()
                    val marker = processingMarker.get()
                    if (marker != null && marker.first === n && nowMs - marker.second < 1000L) {
                        debugSkip("nested")
                        return // nested overload delegation of the same post
                    }
                    processingMarker.set(n to nowMs)
                    val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                        (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                    if (!ongoing) { debugSkip("notOngoing flags=${n.flags}"); return }
                    if (n.contentView != null || n.bigContentView != null || n.headsUpContentView != null) {
                        debugSkip("customView"); return
                    }
                    if (n.extras?.getParcelable(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap != null) {
                        debugSkip("picture"); return
                    }

                    // ...(String pkg, String opPkg, int uid, int pid, String tag, int id, Notification n, int userId...)
                    val pkg = param.args.getOrNull(0) as? String ?: return
                    val id = param.args.getOrNull(notifIdx - 1) as? Int ?: return
                    val tag = param.args.getOrNull(notifIdx - 2) as? String
                    val userId = param.args.getOrNull(notifIdx + 1) as? Int ?: 0
                    val key = "$pkg|$tag|$id|$userId"

                    val hash = contentHash(n)
                    val now = SystemClock.elapsedRealtime()
                    val entry = dedupEntries[key]
                    if (DEBUG_DEDUP) {
                        // XposedBridge.log: logcat E-lines from system_server are unreliable
                        // on this firmware (silenced boot-to-boot), the LSPosed log is not
                        XposedBridge.log(
                            "$TAG NotifDedup EVAL $key hash=$hash prev=${entry?.hash} " +
                                "agePass=${entry?.let { now - it.lastPassMs }} " +
                                "active=${isNotificationActive(param.thisObject, pkg, tag, id)} " +
                                "thread=${Thread.currentThread().name}"
                        )
                    }
                    if (entry != null && entry.hash == hash && now - entry.lastPassMs < FORCE_PASS_MS &&
                        // Only drop if the notification is CURRENTLY showing: a repost after
                        // cancel has the same content but must go through, or it stays gone
                        isNotificationActive(param.thisObject, pkg, tag, id)
                    ) {
                        param.result = null // void method: skip original = drop the update
                        val total = droppedTotal.incrementAndGet()
                        if (DEBUG_DEDUP || total % 25L == 1L) {
                            XposedBridge.log("$TAG NotifDedup: dropped identical update #$total (last: $pkg id=$id)")
                        }
                        return
                    }
                    if (DEBUG_DEDUP && entry != null) {
                        val e = n.extras
                        XposedBridge.log(
                            "$TAG NotifDedup PASS $key hash=$hash prev=${entry.hash} " +
                                "title=${e?.getCharSequence(Notification.EXTRA_TITLE)} " +
                                "text=${e?.getCharSequence(Notification.EXTRA_TEXT)} " +
                                "sub=${e?.getCharSequence(Notification.EXTRA_SUB_TEXT)} " +
                                "when=${n.`when`} showWhen=${e?.getBoolean(Notification.EXTRA_SHOW_WHEN)} " +
                                "flags=${n.flags} color=${n.color} number=${n.number} " +
                                "actions=${n.actions?.size} iconType=${try { n.smallIcon?.type } catch (t: Throwable) { -1 }}"
                        )
                    }
                    if (entry != null) {
                        entry.hash = hash
                        entry.lastPassMs = now
                    } else {
                        if (dedupEntries.size >= MAX_TRACKED) {
                            dedupEntries.keys.take(MAX_TRACKED / 4).forEach { dedupEntries.remove(it) }
                        }
                        dedupEntries[key] = DedupEntry(hash, now)
                    }
                } catch (_: Throwable) {}
            }

        })
        XposedBridge.log("$TAG: NotifDedup hooked ${unhooks.size} enqueueNotificationInternal overloads")
        if (unhooks.isEmpty()) throw IllegalStateException("enqueueNotificationInternal not found")
    }

    /** True if a notification with this (pkg, tag, id) is in the active list. Fail-open: any doubt = false = pass. */
    private fun isNotificationActive(nms: Any, pkg: String, tag: String?, id: Int): Boolean {
        return try {
            val list = XposedHelpers.getObjectField(nms, "mNotificationList") as? List<*> ?: return false
            for (r in list.toList()) {
                r ?: continue
                val sbn = try {
                    XposedHelpers.getObjectField(r, "sbn")
                } catch (_: Throwable) {
                    try { XposedHelpers.callMethod(r, "getSbn") } catch (_: Throwable) { null }
                } ?: continue
                if (XposedHelpers.callMethod(sbn, "getId") != id) continue
                if (XposedHelpers.callMethod(sbn, "getPackageName") != pkg) continue
                if (XposedHelpers.callMethod(sbn, "getTag") != tag) continue
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** Hash of everything the user can see; `when` counts only if it is displayed. */
    @Suppress("DEPRECATION")
    private fun contentHash(n: Notification): Int {
        var h = 1
        fun add(v: Any?) { h = 31 * h + (v?.hashCode() ?: 0) }
        add(try { n.channelId } catch (_: Throwable) { null })
        add(n.flags)
        add(n.color)
        add(n.number)
        add(n.category)
        add(n.visibility)
        add(n.tickerText?.toString())
        val e = n.extras
        if (e != null) {
            for (k in listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_PROGRESS,
                Notification.EXTRA_PROGRESS_MAX,
                Notification.EXTRA_PROGRESS_INDETERMINATE,
                Notification.EXTRA_SHOW_WHEN,
                Notification.EXTRA_SHOW_CHRONOMETER
            )) {
                add(when (val v = e.get(k)) {
                    is CharSequence -> v.toString()
                    else -> v
                })
            }
            (e.get(Notification.EXTRA_TEXT_LINES) as? Array<*>)?.let { lines ->
                lines.forEach { add(it?.toString()) }
            }
            if (e.getBoolean(Notification.EXTRA_SHOW_WHEN, false) ||
                e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            ) {
                add(n.`when`)
            }
        }
        add(n.actions?.size ?: 0)
        n.actions?.forEach { add(it.title?.toString()) }
        n.smallIcon?.let { ic ->
            try {
                add(ic.type)
                if (ic.type == Icon.TYPE_RESOURCE) {
                    add(ic.resId)
                    add(ic.resPackage)
                }
            } catch (_: Throwable) {}
        }
        add(n.getLargeIcon() != null)
        return h
    }
}
