package com.scriptrunner.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helpers for reading the script list and starting / stopping scripts via Termux RUN_COMMAND.
 *
 * Requirements:
 * 1. Termux installed
 * 2. In Termux: echo "allow-external-apps = true" >> ~/.termux/termux.properties
 *    then force-stop Termux and reopen
 * 3. This app must hold com.termux.permission.RUN_COMMAND
 */
object TermuxHelper {

    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    /** Files inside the Termux home folder (~/) that list the scripts (txt is preferred). */
    const val LIST_FILE = "scripts.txt"
    const val LIST_FILE_JSON = "scripts.json"

    private const val HOME = "/data/data/com.termux/files/home"
    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val BASH = "$PREFIX/bin/bash"

    private val EXAMPLE_LIST = """# Script Runner - list of scripts
# One { } block per script. Each line is "key = value". Lines starting with # are comments.
#
#   name        display name
#   path        script path (~ = Termux home)
#   port        port of the script's web server
#   args        extra arguments, separated by spaces          (optional)
#   health      URL path that answers 200 when up, default /  (optional)
#   notify      true = download notifications (/api/jobs)     (optional)
#   unfinished  true = offer to resume interrupted downloads  (optional)
#   share_url   receives shared links, e.g. /?url={url}       (optional)
#   keepalive   notification with a Stop button, default true; false = off (optional)
#   status_bar / nav_bar / background = #RRGGBB               (optional, default: auto from page)

{
  name = Social Downloader
  path = ~/social_downloader/social_downloader_web.py
  port = 5000
  args = --no-browser --idle-exit 120
  health = /api/status
  notify = true
  unfinished = true
  share_url = /?url={url}
},

{
  name = Audio Video Tools
  path = ~/AUDIO_VIDEO_TOOLS/app.py
  port = 5000
  args = --no-browser
  keepalive = true
}"""

    // ---------- list result callback (filled by ScriptListReceiver) ----------
    @Volatile
    var listCallback: ((Boolean, String) -> Unit)? = null

    fun deliverListResult(ok: Boolean, text: String) {
        Handler(Looper.getMainLooper()).post { listCallback?.invoke(ok, text) }
    }

    // ---------- prefs ----------
    private fun prefs(c: Context) = c.getSharedPreferences("av_tools", Context.MODE_PRIVATE)

    fun getDefaultPath(c: Context): String? = prefs(c).getString("default_path", null)

    fun setDefaultPath(c: Context, path: String?) {
        prefs(c).edit().apply {
            if (path == null) remove("default_path") else putString("default_path", path)
        }.apply()
    }

    /** Background colour last measured from a script's page (used before the page has loaded). */
    fun getSampledColor(c: Context, path: String): Int? {
        val p = prefs(c)
        return if (p.contains("col_$path")) p.getInt("col_$path", 0) else null
    }

    fun setSampledColor(c: Context, path: String, color: Int) {
        prefs(c).edit().putInt("col_$path", color).apply()
    }

    fun getCurrent(c: Context): Script? =
        prefs(c).getString("current", null)?.let { Script.fromJson(it) }

    fun setCurrent(c: Context, s: Script?) {
        prefs(c).edit().apply {
            if (s == null) remove("current") else putString("current", s.toJson())
        }.apply()
    }

    // ---------- basics ----------
    fun expand(p: String): String = when {
        p == "~" -> HOME
        p.startsWith("~/") -> HOME + p.substring(1)
        else -> p
    }

    fun urlFor(s: Script) = "http://127.0.0.1:${s.port}"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return context.checkSelfPermission(RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    // cached copy of the list, so ShareActivity can resolve a script without asking Termux
    fun saveListRaw(c: Context, raw: String) {
        prefs(c).edit().putString("list_raw", raw).apply()
    }

    fun getCachedScripts(c: Context): List<Script> =
        Script.parseList(prefs(c).getString("list_raw", "") ?: "")

    /** GET on the script's server; null on any error / non-200. Call off the main thread. */
    fun httpGet(s: Script, path: String): String? = try {
        val c = URL(urlFor(s) + path).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 4000
        if (c.responseCode != 200) {
            c.disconnect()
            null
        } else {
            val b = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            b
        }
    } catch (_: Exception) {
        null
    }

    fun httpPost(s: Script, path: String) {
        try {
            val c = URL(urlFor(s) + path).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 1500
            c.readTimeout = 8000
            c.responseCode
            c.disconnect()
        } catch (_: Exception) { }
    }

    /** True if the script's health URL answers. Call off the main thread. */
    fun isServerAlive(s: Script): Boolean {
        return try {
            val conn = URL(urlFor(s) + s.health).openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    // ---------- commands ----------
    private inline fun guarded(context: Context, what: String, block: () -> Unit): String? {
        if (!isTermuxInstalled(context)) return "Termux is not installed"
        if (!hasRunCommandPermission(context)) return "Missing permission: Run commands in Termux"
        return try {
            block()
            null
        } catch (e: SecurityException) {
            "Permission denied. Allow “Run commands in Termux” for this app."
        } catch (e: Exception) {
            "Failed to $what: ${e.message}"
        }
    }

    private fun buildIntent(
        exe: String,
        args: Array<String>,
        workdir: String,
        result: PendingIntent?
    ) = Intent().apply {
        setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
        action = "com.termux.RUN_COMMAND"
        putExtra("com.termux.RUN_COMMAND_PATH", exe)
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        if (result != null) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
    }

    /**
     * When Termux's process is not running (killed by Android / no session left),
     * a plain startService() is rejected with "app is in background uid null".
     * startForegroundService() is allowed from a visible app and RunCommandService
     * promotes itself to foreground, so it works even when Termux is dead.
     */
    private fun dispatch(context: Context, intent: Intent) {
        try {
            context.startForegroundService(intent)
        } catch (_: IllegalStateException) {
            context.startService(intent)
        }
    }

    private fun send(
        context: Context,
        exe: String,
        args: Array<String>,
        workdir: String,
        result: PendingIntent? = null
    ) {
        val intent = buildIntent(exe, args, workdir, result)
        try {
            dispatch(context, intent)
        } catch (e: IllegalStateException) {
            // Last resort: wake Termux by opening it, retry, then return to our app.
            openTermux(context)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    dispatch(context, buildIntent(exe, args, workdir, result))
                } catch (_: Exception) { }
                if (context is android.app.Activity && context !is ShareActivity) {
                    try {
                        context.startActivity(
                            Intent(context, context.javaClass)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    } catch (_: Exception) { }
                }
            }, 1800)
        }
    }

    /**
     * Asks Termux to print ~/scripts.txt (creating an example file if it does not exist).
     * The output arrives asynchronously via [listCallback].
     * @return null if the request was sent, or an error message
     */
    fun readScriptList(context: Context): String? = guarded(context, "read script list") {
        val example = EXAMPLE_LIST
        val cmd = "cd \"\$HOME\"; " +
            "if [ -f $LIST_FILE ]; then cat $LIST_FILE; " +
            "elif [ -f $LIST_FILE_JSON ]; then cat $LIST_FILE_JSON; " +
            "else cat > $LIST_FILE <<'SR_EOF'\n$example\nSR_EOF\ncat $LIST_FILE; fi"
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            context, 100, Intent(context, ScriptListReceiver::class.java), flags
        )
        send(context, BASH, arrayOf("-c", cmd), HOME, pi)
    }

    fun startServer(context: Context, s: Script): String? = guarded(context, "start") {
        val path = expand(s.path)
        val interp = s.interpreter ?: when (path.substringAfterLast('.', "").lowercase()) {
            "py" -> "python"
            "js", "mjs" -> "node"
            "sh" -> "bash"
            else -> null
        }
        val exe: String
        val args: Array<String>
        if (interp != null) {
            exe = if (interp.startsWith("/")) interp else "$PREFIX/bin/$interp"
            args = arrayOf(path) + s.args.toTypedArray()
        } else {
            exe = path
            args = s.args.toTypedArray()
        }
        val wd = s.workdir?.let { expand(it) }
            ?: path.substringBeforeLast('/', HOME).ifEmpty { HOME }
        send(context, exe, args, wd)
    }

    fun stopServer(context: Context, s: Script): String? = guarded(context, "stop") {
        val pattern = expand(s.path).replace("'", "'\\''")
        send(context, BASH, arrayOf("-c", "pkill -f -- '$pattern' || true"), HOME)
    }

    fun openTermux(context: Context) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (launch != null) {
                context.startActivity(launch)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TERMUX_PACKAGE"))
                )
            }
        } catch (_: Exception) { }
    }

    fun openAppPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) { }
    }
}
