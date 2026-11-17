package com.scriptrunner.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bottom-sheet overlay shown on top of another app (e.g. YouTube) when a link is shared to
 * Script Runner. The link goes to the script that has "share_url" set in ~/scripts.txt.
 */
class ShareActivity : Activity() {

    companion object {
        private const val REQ_PERM = 43
        private const val MAX_WAIT_SEC = 90
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onStart() { super.onStart(); DownloadMonitorService.uiVisible++; recheckServer() }
    override fun onStop() { super.onStop(); DownloadMonitorService.uiVisible-- }
    @Volatile private var polling = false
    private var sharedUrl: String? = null
    private var script: Script? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun extractUrl(i: Intent?): String? {
        if (i == null) return null
        val raw = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.dataString ?: return null
        return Regex("https?://\\S+").find(raw)?.value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUrl = extractUrl(intent)
        script = pickShareScript()
        val colors = resolveColors(this, script)
        applyBarColors(colors[0], colors[1])
        val sheetBg = colors[2]
        val fg = if (isLightColor(sheetBg)) Color.BLACK else Color.WHITE

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#66000000"))
        root.setOnClickListener { finish() }          // tap outside = close

        val r = dp(20).toFloat()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true                         // don't leak taps to root
            background = GradientDrawable().apply {
                setColor(sheetBg)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }

        // Header: title + close button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(4), dp(0))
        }
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(fg)
            textSize = 20f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { finish() }
        })
        sheet.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)
        web = WebView(this).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(sheetBg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun close() {
                    this@ShareActivity.runOnUiThread { this@ShareActivity.finish() }
                }
            }, "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                    val host = Uri.parse(url).host
                    if (host == "127.0.0.1" || host == "localhost") return false
                    openExternal(url)
                    return true
                }
            }
            setDownloadListener { url, _, _, _, _ -> openExternal(url) }
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(v: WebView, url: String, message: String, res: JsResult): Boolean {
                    AlertDialog.Builder(this@ShareActivity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> res.confirm() }
                        .setOnCancelListener { res.cancel() }
                        .show()
                    return true
                }
                override fun onJsConfirm(v: WebView, url: String, message: String, res: JsResult): Boolean {
                    AlertDialog.Builder(this@ShareActivity)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> res.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> res.cancel() }
                        .setOnCancelListener { res.cancel() }
                        .show()
                    return true
                }
            }
        }
        status = TextView(this).apply {
            setTextColor(fg)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener { if (!polling) begin() }
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        content.addView(status, FrameLayout.LayoutParams(-1, -1))
        sheet.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val sheetH = (resources.displayMetrics.heightPixels * 0.9).toInt()
        root.addView(sheet, FrameLayout.LayoutParams(-1, sheetH, Gravity.BOTTOM))
        setContentView(root)

        begin()
    }

    /** The script that receives shared links: the default one if it has share_url, else the first that has it. */
    private fun pickShareScript(): Script? {
        val list = TermuxHelper.getCachedScripts(this).filter { it.shareUrl != null }
        val def = TermuxHelper.getDefaultPath(this)
        return list.firstOrNull { it.path == def } ?: list.firstOrNull()
    }

    /** The server may have exited after idling; restart it when we come back. */
    private fun recheckServer() {
        val s = script ?: return
        if (!::web.isInitialized || web.visibility != View.VISIBLE || polling) return
        Thread {
            if (!TermuxHelper.isServerAlive(s)) ui.post {
                web.visibility = View.INVISIBLE
                begin()
            }
        }.start()
    }

    private fun setStatus(msg: String) = ui.post {
        status.visibility = View.VISIBLE
        status.text = msg
    }

    private fun begin() {
        if (script == null) {
            setStatus(getString(R.string.err_no_share_script))
            return
        }
        if (!TermuxHelper.isTermuxInstalled(this)) {
            setStatus(getString(R.string.err_no_termux))
            return
        }
        val need = Perms.missing(this)
        if (need.isNotEmpty()) {
            setStatus(getString(R.string.waiting_perms))
            requestPermissions(need, REQ_PERM)
            return
        }
        startFlow()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERM) {
            if (TermuxHelper.hasRunCommandPermission(this)) startFlow()
            else setStatus(getString(R.string.err_no_permission))
        }
    }

    private fun startFlow() {
        val s = script ?: return
        polling = true
        setStatus(getString(R.string.starting, s.name))
        Thread {
            // only one script at a time: stop whatever else is selected
            val cur = TermuxHelper.getCurrent(this)
            if (cur != null && cur.path != s.path) {
                TermuxHelper.stopServer(this, cur)
                Thread.sleep(1200)
            }
            TermuxHelper.setCurrent(this, s)

            if (!TermuxHelper.isServerAlive(s)) {
                val err = TermuxHelper.startServer(this, s)
                if (err != null) {
                    polling = false
                    setStatus(err)
                    return@Thread
                }
            }
            var waited = 0
            while (waited < MAX_WAIT_SEC && !isFinishing) {
                if (TermuxHelper.isServerAlive(s)) {
                    ui.post {
                        if (isFinishing) return@post
                        status.visibility = View.GONE
                        web.visibility = View.VISIBLE
                        if (s.monitor) DownloadMonitorService.start(this)
                        val u = sharedUrl
                        var path = if (u != null) s.shareUrl!!.replace("{url}", Uri.encode(u)) else "/"
                        if (!path.startsWith("/")) path = "/$path"
                        web.loadUrl(TermuxHelper.urlFor(s) + path)
                    }
                    polling = false
                    return@Thread
                }
                setStatus("${getString(R.string.starting, s.name)} ${waited}s")
                Thread.sleep(1000)
                waited++
            }
            polling = false
            if (!isFinishing) setStatus(getString(R.string.server_timeout))
        }.start()
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else finish()
    }
}
