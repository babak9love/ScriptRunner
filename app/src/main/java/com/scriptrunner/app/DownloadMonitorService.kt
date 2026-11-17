package com.scriptrunner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls the Termux server's /api/jobs and mirrors download progress in a
 * notification. The notification only exists while a download is active.
 */
class DownloadMonitorService : Service() {

    companion object {
        @Volatile var uiVisible = 0
        private const val TAG = "SocialDL"
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val FG_ID = 1
        private const val WARN_ID = 3000
        const val ACTION_CANCEL = "com.scriptrunner.app.CANCEL_ALL"
        const val ACTION_PAUSE = "com.scriptrunner.app.PAUSE_ALL"
        const val ACTION_RESUME = "com.scriptrunner.app.RESUME_ALL"
        const val ACTION_CANCEL_JOB = "com.scriptrunner.app.CANCEL_JOB"
        const val ACTION_PAUSE_JOB = "com.scriptrunner.app.PAUSE_JOB"
        const val ACTION_RESUME_JOB = "com.scriptrunner.app.RESUME_JOB"
        private const val EXTRA_JOB_ID = "job_id"
        const val ACTION_REVIVE = "com.scriptrunner.app.REVIVE_NOTIF"
        private const val EXTRA_NID = "nid"
        private const val GROUP_KEY = "downloads"
        private const val CHILD_BASE = 100000
        private const val MAX_CHILD_NOTIFS = 5
        private const val CONTROL_ID = 90000       // standalone "all downloads" notification
        private const val CONTROL_MIN_ACTIVE = 3    // shown when more than 2 downloads run
        private const val ROLE_NONE = 0
        private const val ROLE_SUMMARY = 1
        private const val ROLE_CHILD = 2
        private val ACTIVE = setOf("queued", "starting", "downloading", "paused")

        fun start(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, DownloadMonitorService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "cannot start monitor", e)
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var isForeground = false
    @Volatile private var outdated = false
    /** job id -> notification id of the per-download notifications currently shown. */
    private val childNotifs = HashMap<String, Int>()
    // Android sheds notification updates when an app posts too fast (~5/s), which froze
    // the last notification. So: skip unchanged content, throttle pure progress updates,
    // and space the posts out.
    private val structSig = HashMap<Int, String>()
    private val progSig = HashMap<Int, String>()
    private val lastPostAt = HashMap<Int, Long>()
    private val buttonsKeys = HashMap<Int, String>()   // nid -> last posted button-relevant state

    override fun onBind(intent: Intent?): IBinder? = null

    /** http://127.0.0.1:<port> of the selected script, or null if it has no notifications enabled. */
    private fun base(): String? =
        TermuxHelper.getCurrent(this)?.takeIf { it.monitor }?.let { TermuxHelper.urlFor(it) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        val path = when (intent?.action) {
            ACTION_CANCEL -> "cancel_all"
            ACTION_PAUSE -> "pause_all"
            ACTION_RESUME -> "resume_all"
            ACTION_CANCEL_JOB -> jobId?.let { "cancel/" + Uri.encode(it) }
            ACTION_PAUSE_JOB -> jobId?.let { "pause/" + Uri.encode(it) }
            ACTION_RESUME_JOB -> jobId?.let { "resume/" + Uri.encode(it) }
            else -> null
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
        )

        if (path != null) {
            Thread { postAction(path) }.start()
            // The system (or the device's battery manager) can kill this service in the
            // background — logcat shows it as "Stop FGS timeout". When that happens the
            // polling loop dies with it, so notifications freeze even though pause/resume/
            // cancel still reach the server fine (a fresh short-lived instance handles the
            // button tap). Revive the loop here so any button tap also un-freezes the UI.
            if (!running && base() != null) {
                running = true
                Thread { loop() }.start()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REVIVE) {
            // The user swiped an active-download notification away — forget its last
            // posted state so the next tick reposts it (only while the loop is running,
            // i.e. only while something is actually still active; a dead loop means
            // nothing is downloading, so there's nothing to revive).
            val nid = intent.getIntExtra(EXTRA_NID, -1)
            if (nid != -1) {
                structSig.remove(nid)
                progSig.remove(nid)
            }
            if (!running && base() != null) {
                running = true
                Thread { loop() }.start()
            }
            return START_NOT_STICKY
        }
        if (intent?.action != null && intent.action != Intent.ACTION_MAIN) {
            // per-job action without an id: ignore
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, DownloadMonitorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Per-download action. Unique data URI keeps each job's PendingIntent distinct. */
    private fun jobActionPi(action: String, jobId: String): PendingIntent = PendingIntent.getService(
        this, 0,
        Intent(this, DownloadMonitorService::class.java)
            .setAction(action)
            .setData(Uri.parse("job://" + Uri.encode(jobId) + "/" + action))
            .putExtra(EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifAction(iconRes: Int, label: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, pi).build()

    /** Fired if the user swipes an active-download notification away — reposts it while
     * the download is still running, so an accidental dismissal doesn't lose the controls.
     * Never attached to the "download finished" notification, so a completed download
     * stays dismissed once swiped, as intended. */
    private fun revivePi(nid: Int): PendingIntent = PendingIntent.getService(
        this, 0,
        Intent(this, DownloadMonitorService::class.java)
            .setAction(ACTION_REVIVE)
            .setData(Uri.parse("revive://$nid"))
            .putExtra(EXTRA_NID, nid),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotif(
        nid: Int,
        title: String, text: String, pct: Int, indeterminate: Boolean,
        paused: Boolean, many: Boolean,
        jobId: String? = null, role: Int = ROLE_NONE, mixed: Boolean = false
    ): Notification {
        val suffix = if (many) " all" else ""
        val toggle = if (paused) {
            notifAction(
                android.R.drawable.ic_media_play, "Resume$suffix",
                if (jobId != null) jobActionPi(ACTION_RESUME_JOB, jobId) else actionPi(ACTION_RESUME, 2)
            )
        } else {
            notifAction(
                android.R.drawable.ic_media_pause, "Pause$suffix",
                if (jobId != null) jobActionPi(ACTION_PAUSE_JOB, jobId) else actionPi(ACTION_PAUSE, 3)
            )
        }
        val cancel = notifAction(
            android.R.drawable.ic_menu_close_clear_cancel, "Cancel$suffix",
            if (jobId != null) jobActionPi(ACTION_CANCEL_JOB, jobId) else actionPi(ACTION_CANCEL, 1)
        )
        // Mixed state (some paused, some not): offer Pause all AND Resume all.
        val resumeAll = if (mixed && jobId == null) notifAction(
            android.R.drawable.ic_media_play, "Resume all", actionPi(ACTION_RESUME, 2)
        ) else null
        val toggleAction = if (mixed && jobId == null) notifAction(
            android.R.drawable.ic_media_pause, "Pause all", actionPi(ACTION_PAUSE, 3)
        ) else toggle
        val b = Notification.Builder(this, CH_PROGRESS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())   // forces some OEM UIs to redraw the action row
            .setSmallIcon(if (paused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(revivePi(nid))
            .addAction(toggleAction)
        if (resumeAll != null) b.addAction(resumeAll)
        b.addAction(cancel)
        when (role) {
            ROLE_SUMMARY -> b.setGroup(GROUP_KEY).setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            ROLE_CHILD -> b.setGroup(GROUP_KEY)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
        return b.build()
    }

    /** Paused, or pause requested but yt-dlp has not reached the hook yet (e.g. stalled network). */
    private fun isPaused(j: JSONObject): Boolean {
        val st = j.optString("status")
        return st == "paused" || (j.optBoolean("pause_requested", false) && st in ACTIVE)
    }

    private fun jobText(j: JSONObject): Pair<Int, String> {
        val pct = j.optDouble("percent", 0.0).toInt().coerceIn(0, 100)
        val st = j.optString("status")
        val msg = j.optString("message")
        val speed = j.optDouble("speed", 0.0)
        val text = when {
            st == "paused" -> "Paused · $pct%"
            isPaused(j) -> "Pausing… · $pct%"
            msg.endsWith("%") ->
                "$pct%" + if (speed > 0) " · %.1f MB/s".format(speed / 1024 / 1024) else ""
            else -> "$msg ($pct%)"
        }
        return pct to text
    }

    /** Posts only when needed. [struct] changes go out at once; [prog] changes at most every 2 s. */
    private fun post(
        nm: NotificationManager, nid: Int, struct: String, prog: String, n: Notification,
        // null (default) = never cancel+recreate for this notification. Only the
        // "All downloads" control notification opts in, by passing a real key.
        buttonsKey: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        val structChanged = structSig[nid] != struct
        val progChanged = progSig[nid] != prog
        if (!structChanged) {
            if (!progChanged) return false
            if (now - (lastPostAt[nid] ?: 0L) < 2000L) return false
        }
        try {
            // One UI (and some other OEM skins) can silently fail to redraw a
            // notification's action-button row on a plain notify() update, even
            // though the call succeeds — the label stays stuck on the old text.
            // Cancelling first forces a full re-post, which reliably refreshes it.
            // Only do this when the button label itself may have changed
            // (buttonsKey), not on a bare progress tick — cancelling on every
            // progress update would make the notification flicker/reorder.
            if (buttonsKey != null) {
                val buttonsChanged = buttonsKeys[nid] != null && buttonsKeys[nid] != buttonsKey
                if (buttonsChanged) nm.cancel(nid)
                buttonsKeys[nid] = buttonsKey
            }
            nm.notify(nid, n)
        } catch (e: Throwable) {
            Log.e(TAG, "notify($nid) failed", e)
            return false   // don't record sigs — retry next tick
        }
        structSig[nid] = struct
        progSig[nid] = prog
        lastPostAt[nid] = now
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        return true
    }

    private fun forget(nid: Int) {
        structSig.remove(nid); progSig.remove(nid); lastPostAt.remove(nid); buttonsKeys.remove(nid)
    }

    private fun childId(jobId: String): Int = CHILD_BASE + (jobId.hashCode() and 0xffff)

    /** One notification per active download (only used while 2+ are running). */
    private fun syncChildNotifs(nm: NotificationManager, active: List<JSONObject>) {
        val wanted = HashSet<String>()
        for (j in active.take(MAX_CHILD_NOTIFS)) {
            val id = j.optString("id")
            if (id.isEmpty()) continue
            wanted.add(id)
            // Isolated per download: one job's notification failing must not stop the
            // rest of the batch from updating (this is how one item used to get "stuck").
            try {
                val st = j.optString("status")
                val (pct, text) = jobText(j)
                val title = j.optString("title", "Downloading").ifBlank { "Downloading" }
                val nid = childId(id)
                val n = progressNotif(
                    nid, title, text, pct, st == "queued" || st == "starting", isPaused(j), false,
                    jobId = id, role = ROLE_CHILD
                )
                childNotifs[id] = nid
                post(nm, nid, "$title|${isPaused(j)}|${st == "queued" || st == "starting"}", "$pct|$text", n)
                // Per-download notifications update reliably with a plain notify() —
                // only the single "commander" notification (control + FG summary)
                // needed the cancel+recreate workaround.
            } catch (e: Throwable) {
                Log.e(TAG, "child notif failed for job ${id.takeLast(8)}", e)
            }
        }
        clearChildNotifs(nm, keep = wanted)
    }

    /** Extra standalone notification controlling every running download (shown when 3+ run). */
    private fun syncControlNotif(nm: NotificationManager, active: List<JSONObject>, allPaused: Boolean) {
        if (active.size < CONTROL_MIN_ACTIVE) {
            nm.cancel(CONTROL_ID)
            forget(CONTROL_ID)
            return
        }
        val avg = active.map { it.optDouble("percent", 0.0) }.average().toInt().coerceIn(0, 100)
        val paused = active.count { isPaused(it) }
        val text = when {
            allPaused -> "Paused · $avg%"
            paused > 0 -> "$avg% · $paused paused"
            else -> "$avg%"
        }
        val n = progressNotif(
            CONTROL_ID,
            "All downloads (${active.size})", text, avg, false, allPaused, true,
            jobId = null, role = ROLE_NONE, mixed = paused > 0 && !allPaused
        )
        post(nm, CONTROL_ID, "ctl|$allPaused|${active.size}|$paused", "$avg", n,
            buttonsKey = "$allPaused|${paused > 0 && !allPaused}")
    }

    private fun clearChildNotifs(nm: NotificationManager, keep: Set<String> = emptySet()) {
        val it = childNotifs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                nm.cancel(e.value)
                forget(e.value)
                it.remove()
            }
        }
    }

    private fun postAction(path: String) {
        try {
            val b = base() ?: return
            val c = URL("$b/api/$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 1500
            c.readTimeout = 3000
            c.responseCode
            c.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "action $path failed", e)
        }
    }

    /** null = error (see [outdated] for a missing /api/jobs endpoint). */
    private fun fetchJobs(): JSONArray? {
        val b = base() ?: return null
        return try {
            val c = URL("$b/api/jobs").openConnection() as HttpURLConnection
            c.connectTimeout = 800
            c.readTimeout = 1500
            if (c.responseCode == 404) {
                outdated = true
                c.disconnect()
                null
            } else {
                outdated = false
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                JSONArray(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loop() {
        val nm = getSystemService(NotificationManager::class.java)
        val prev = HashMap<String, String>()
        var first = true
        var failures = 0
        var idleSince = System.currentTimeMillis()

        while (running) {
            if (base() == null) break   // another script was selected
            try {
                val arr = fetchJobs()
                if (arr == null) {
                    failures++
                    if (outdated) {
                        warnOutdated(nm)
                        break
                    }
                } else {
                    failures = 0
                    val active = ArrayList<JSONObject>()
                    for (i in 0 until arr.length()) {
                        val j = arr.getJSONObject(i)
                        val id = j.optString("id")
                        val st = j.optString("status")
                        val old = prev[id]
                        if (!first && (old == null || old in ACTIVE)) {
                            if (st == "done") notifyFinished(nm, id, j, true)
                            else if (st == "error") notifyFinished(nm, id, j, false)
                        }
                        prev[id] = st
                        if (st in ACTIVE) active.add(j)
                    }
                    first = false
                    updateProgress(nm, active)
                    if (active.isNotEmpty()) idleSince = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "monitor loop error", t)
                failures++
            }

            if (uiVisible > 0) idleSince = System.currentTimeMillis()
            val idleFor = System.currentTimeMillis() - idleSince
            if (uiVisible <= 0 && (idleFor > 20_000 || failures > 15)) break
            if (failures > 60) break
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
        }
        running = false
        leaveForeground(nm)
        stopSelf()
    }

    private fun leaveForeground(nm: NotificationManager) {
        try {
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            nm.cancel(FG_ID)
            forget(FG_ID)
            nm.cancel(CONTROL_ID)
            forget(CONTROL_ID)
            clearChildNotifs(nm)
        } catch (e: Exception) {
            Log.e(TAG, "leaveForeground", e)
        }
    }

    private fun updateProgress(nm: NotificationManager, active: List<JSONObject>) {
        if (active.isEmpty()) {
            leaveForeground(nm)
            return
        }
        val many = active.size > 1
        val allPaused = active.all { isPaused(it) }
        val pausedCnt = active.count { isPaused(it) }
        val mixed = many && pausedCnt > 0 && !allPaused
        val n = if (!many) {
            val j = active[0]
            val st = j.optString("status")
            val (pct, text) = jobText(j)
            val id = j.optString("id").ifEmpty { null }
            progressNotif(
                FG_ID,
                j.optString("title", "Downloading").ifBlank { "Downloading" },
                text, pct, st == "queued" || st == "starting", isPaused(j), false,
                jobId = id, role = ROLE_NONE
            )
        } else {
            val avg = active.map { it.optDouble("percent", 0.0) }.average().toInt().coerceIn(0, 100)
            progressNotif(
                FG_ID,
                "${active.size} downloads",
                if (allPaused) "Paused · $avg%" else if (mixed) "$avg% · $pausedCnt paused" else "$avg%",
                avg, false, allPaused, true, jobId = null, role = ROLE_SUMMARY, mixed = mixed
            )
        }
        // 2+ downloads: summary (above) + one notification each. 1 download: single notification.
        // Each stage is isolated: if syncing one set of notifications throws (e.g. the
        // system briefly refuses a post), the others still update instead of the whole
        // notification state freezing for that cycle.
        try {
            if (many) syncChildNotifs(nm, active) else clearChildNotifs(nm)
        } catch (e: Throwable) {
            Log.e(TAG, "syncChildNotifs failed (active=${active.size})", e)
        }
        try {
            syncControlNotif(nm, active, allPaused)
        } catch (e: Throwable) {
            Log.e(TAG, "syncControlNotif failed (active=${active.size})", e)
        }

        val first = active[0]
        val fgStruct = if (many) "many|$allPaused|${active.size}|$pausedCnt"
            else "one|${first.optString("id")}|${first.optString("title")}|${first.optString("status")}"
        val fgProg = if (many) "${active.size}|${active.map { it.optDouble("percent", 0.0) }.average().toInt()}"
            else jobText(first).let { "${it.first}|${it.second}" }

        if (!isForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(FG_ID, n)
                }
                isForeground = true
                structSig[FG_ID] = fgStruct
                progSig[FG_ID] = fgProg
                lastPostAt[FG_ID] = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed, using plain notification", e)
                nm.notify(FG_ID, n)
            }
        } else {
            post(nm, FG_ID, fgStruct, fgProg, n)
        }
    }

    private fun warnOutdated(nm: NotificationManager) {
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Script has no /api/jobs")
            .setContentText("Update the script (or force-stop Termux) and open this app again.")
            .setAutoCancel(true)
            .build()
        nm.notify(WARN_ID, n)
    }

    private fun notifyFinished(nm: NotificationManager, id: String, j: JSONObject, ok: Boolean) {
        val title = j.optString("title", "").ifBlank { "Media" }
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "Download complete" else "Download failed")
            .setContentText(if (ok) title else j.optString("message", title))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(2000 + (id.hashCode() and 0xffff), n)
    }
}
