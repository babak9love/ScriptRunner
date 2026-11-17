package com.scriptrunner.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var webView: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHint: TextView
    private lateinit var btnStartServer: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnOpenTermux: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTopRefresh: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var isServerReady = false
    private var checkAttempts = 0

    private var scripts: List<Script> = emptyList()
    private var current: Script? = null
    private var waitingList = false
    private var displayedPath: String? = null
    /** false while reloading the list from the settings menu (do not auto-launch / cover the page) */
    private var autoLaunch = true

    /** pending callback for an in-page <input type="file"> the WebView asked us to resolve */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val MAX_CHECKS = 180
        private const val CHECK_INTERVAL_MS = 500L
        private const val LIST_TIMEOUT_MS = 15000L
        private const val REQ_PERM = 42
        private const val REQ_FILE_CHOOSER = 43
    }

    private val listTimeout = Runnable {
        if (waitingList) {
            waitingList = false
            TermuxHelper.listCallback = null
            fail(getString(R.string.list_timeout))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.root)
        topBar = findViewById(R.id.topBar)
        webView = findViewById(R.id.webView)
        overlay = findViewById(R.id.overlay)
        progressBar = findViewById(R.id.progressBar)
        tvHint = findViewById(R.id.tvHint)
        btnStartServer = findViewById(R.id.btnStartServer)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnOpenTermux = findViewById(R.id.btnOpenTermux)
        btnSettings = findViewById(R.id.btnSettings)
        btnTopRefresh = findViewById(R.id.btnTopRefresh)

        current = TermuxHelper.getCurrent(this)
        applyTheme(current)

        setupWebView()
        setupButtons()
        bootstrap()
    }

    override fun onStart() {
        super.onStart()
        DownloadMonitorService.uiVisible++
        val c = TermuxHelper.getCurrent(this)
        if (isServerReady && c != null && c.path != displayedPath) {
            // another script was started elsewhere (e.g. the share sheet)
            current = c
            launch(c)
        } else {
            recheckServer()
        }
    }

    override fun onStop() {
        super.onStop()
        DownloadMonitorService.uiVisible--
    }

    /** The server may have exited after idling; restart it when we come back. */
    private fun recheckServer() {
        val s = current ?: return
        if (!isServerReady || waitingList) return
        executor.execute {
            if (!TermuxHelper.isServerAlive(s)) runOnUiThread { launch(s) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (TermuxHelper.hasRunCommandPermission(this)) {
                loadScriptList()
            } else {
                showMessage(getString(R.string.err_no_permission))
            }
        }
    }

    // ------------------------------------------------------------------ UI helpers

    /**
     * Paints status bar, navigation bar, top strip and overlay with the script's colours
     * (explicit in scripts.txt, else measured from the page, else default).
     */
    private fun applyTheme(s: Script?, sampled: Int? = null) {
        val c = resolveColors(this, s, sampled)
        applyBarColors(c[0], c[1])
        val bg = c[2]
        val light = isLightColor(bg)
        rootLayout.setBackgroundColor(bg)
        topBar.setBackgroundColor(bg)
        webView.setBackgroundColor(bg)
        overlay.setBackgroundColor((0xE6 shl 24) or (bg and 0x00FFFFFF))
        tvHint.setTextColor(if (light) 0xFF1F2933.toInt() else 0xFFC9D3DD.toInt())
        val iconColor = if (light) 0xFF1F2933.toInt() else 0xFFE8EDF2.toInt()
        btnSettings.setColorFilter(iconColor)
        btnTopRefresh.setColorFilter(iconColor)
    }

    /** Reads the page's background colour and re-themes the bars to match. */
    private fun sampleThemeColor(s: Script) {
        val js = "(function(){function g(e){return getComputedStyle(e).backgroundColor}" +
            "var c=g(document.body);" +
            "if(!c||c==='transparent'||c==='rgba(0, 0, 0, 0)'){c=g(document.documentElement)}" +
            "if(!c||c==='transparent'||c==='rgba(0, 0, 0, 0)'){c='rgb(255, 255, 255)'}" +
            "return c})()"
        webView.evaluateJavascript(js) { raw ->
            val n = Regex("[0-9.]+").findAll(raw ?: "").map { it.value.toDouble() }.toList()
            if (n.size >= 3 && (n.size < 4 || n[3] > 0.0) && current?.path == s.path) {
                val col = Color.rgb(
                    n[0].toInt().coerceIn(0, 255),
                    n[1].toInt().coerceIn(0, 255),
                    n[2].toInt().coerceIn(0, 255)
                )
                TermuxHelper.setSampledColor(this, s.path, col)
                applyTheme(s, col)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showMessage(text: String, busy: Boolean = false) {
        tvHint.text = text
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        btnStartServer.visibility = if (busy) View.GONE else View.VISIBLE
        overlay.visibility = View.VISIBLE
    }

    private fun setServerRunningUi(running: Boolean) {
        isServerReady = running
        if (running) {
            btnStartServer.visibility = View.GONE
            overlay.visibility = View.GONE
        } else {
            btnStartServer.visibility = View.VISIBLE
        }
    }

    /** Error while reading the list: cover the screen on startup, only toast on a silent reload. */
    private fun fail(msg: String) {
        if (autoLaunch) showMessage(msg) else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        autoLaunch = true
    }

    // ------------------------------------------------------------------ startup / list

    private fun bootstrap() {
        if (!TermuxHelper.isTermuxInstalled(this)) {
            showMessage(getString(R.string.err_no_termux))
            return
        }
        val need = Perms.missing(this)
        if (need.isNotEmpty()) {
            showMessage(getString(R.string.waiting_perms), true)
            requestPermissions(need, REQ_PERM)
            return
        }
        loadScriptList()
    }

    private fun loadScriptList() {
        if (autoLaunch) showMessage(getString(R.string.reading_list), true)
        waitingList = true
        TermuxHelper.listCallback = { ok, text -> onListResult(ok, text) }
        val err = TermuxHelper.readScriptList(this)
        if (err != null) {
            waitingList = false
            TermuxHelper.listCallback = null
            fail(err)
            return
        }
        handler.removeCallbacks(listTimeout)
        handler.postDelayed(listTimeout, LIST_TIMEOUT_MS)
    }

    private fun onListResult(ok: Boolean, text: String) {
        if (!waitingList) return
        waitingList = false
        TermuxHelper.listCallback = null
        handler.removeCallbacks(listTimeout)

        if (!ok) {
            fail(getString(R.string.err_read_list, text))
            return
        }
        scripts = Script.parseList(text)
        if (scripts.isNotEmpty()) TermuxHelper.saveListRaw(this, text)
        if (scripts.isEmpty()) {
            fail(getString(R.string.no_scripts))
            return
        }
        if (!autoLaunch) {
            autoLaunch = true
            Toast.makeText(this, getString(R.string.list_loaded, scripts.size), Toast.LENGTH_SHORT).show()
            return
        }
        val defPath = TermuxHelper.getDefaultPath(this)
        val def = scripts.firstOrNull { it.path == defPath }
        if (def != null) {
            switchTo(def)
        } else {
            showMessage(getString(R.string.choose_hint))
            showPicker()
        }
    }

    // ------------------------------------------------------------------ run / stop

    private fun switchTo(s: Script) {
        val cur = current
        if (cur != null && cur.path != s.path) {
            TermuxHelper.stopServer(this, cur)
            handler.removeCallbacksAndMessages(null)
            isServerReady = false
            webView.loadUrl("about:blank")
            showMessage(getString(R.string.switching, s.name), true)
            handler.postDelayed({ launch(s) }, 1200)
        } else {
            launch(s)
        }
    }

    private fun launch(s: Script) {
        current = s
        TermuxHelper.setCurrent(this, s)
        applyTheme(s)
        isServerReady = false
        showMessage(getString(R.string.starting, s.name), true)
        if (s.keepalive) ServerKeepAliveService.start(this) else ServerKeepAliveService.stop(this)

        executor.execute {
            val alive = TermuxHelper.isServerAlive(s)
            runOnUiThread {
                if (alive) {
                    onServerUp(s)
                } else {
                    val err = TermuxHelper.startServer(this, s)
                    if (err != null) {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        showMessage(err)
                    } else {
                        startPollingServer()
                    }
                }
            }
        }
    }

    private fun onServerUp(s: Script) {
        displayedPath = s.path
        webView.loadUrl(TermuxHelper.urlFor(s))
        setServerRunningUi(true)
        if (s.monitor) DownloadMonitorService.start(this)
    }

    private fun stopCurrent() {
        current?.let { TermuxHelper.stopServer(this, it) }
        ServerKeepAliveService.stop(this)
        handler.removeCallbacksAndMessages(null)
        isServerReady = false
        webView.loadUrl("about:blank")
        showMessage(getString(R.string.stopped))
    }

    private fun startPollingServer() {
        handler.removeCallbacksAndMessages(null)
        checkAttempts = 0
        pollOnce()
    }

    private fun pollOnce() {
        val s = current ?: return
        if (isServerReady) return
        if (checkAttempts >= MAX_CHECKS) {
            showMessage(getString(R.string.server_timeout))
            return
        }
        checkAttempts++
        executor.execute {
            val alive = TermuxHelper.isServerAlive(s)
            runOnUiThread {
                if (alive) {
                    onServerUp(s)
                } else {
                    handler.postDelayed({ pollOnce() }, CHECK_INTERVAL_MS)
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showPicker() {
        if (scripts.isEmpty()) {
            loadScriptList()
            return
        }
        val bg = resolveColors(this, current)[2]
        val sheet = Sheet(this, SheetColors(bg))
        sheet.title(getString(R.string.select_script), getString(R.string.pick_sub), null)
        sheet.picker(scripts, current?.path, TermuxHelper.getDefaultPath(this)) { s, always ->
            if (always) TermuxHelper.setDefaultPath(this, s.path)
            switchTo(s)
        }
        sheet.show()
    }

    private fun showSettingsMenu() {
        val defPath = TermuxHelper.getDefaultPath(this)
        val cur = current
        val bg = resolveColors(this, cur)[2]
        val sheet = Sheet(this, SheetColors(bg))
        val c = sheet.c

        val status = when {
            cur == null -> getString(R.string.no_script_selected)
            isServerReady -> "${cur.name}  ·  ${getString(R.string.state_running)}"
            else -> "${cur.name}  ·  ${getString(R.string.state_stopped)}"
        }
        sheet.title(getString(R.string.settings), status, if (cur == null) null else if (isServerReady) c.ok else c.sub)

        val countSub = if (scripts.isEmpty()) null else getString(R.string.n_scripts, scripts.size)
        sheet.row(R.drawable.ic_swap, getString(R.string.menu_choose), countSub) { showPicker() }

        if (cur != null && cur.path != defPath) {
            sheet.row(R.drawable.ic_star, getString(R.string.set_default), cur.name) {
                TermuxHelper.setDefaultPath(this, cur.path)
                Toast.makeText(this, getString(R.string.default_saved, cur.name), Toast.LENGTH_SHORT).show()
            }
        }
        if (defPath != null) {
            val defName = scripts.firstOrNull { it.path == defPath }?.name ?: defPath
            sheet.row(
                R.drawable.ic_star_border,
                getString(R.string.clear_default),
                getString(R.string.default_now, defName)
            ) {
                TermuxHelper.setDefaultPath(this, null)
                Toast.makeText(this, R.string.default_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        sheet.row(R.drawable.ic_refresh, getString(R.string.menu_reload), getString(R.string.reload_sub)) {
            autoLaunch = false
            loadScriptList()
        }
        sheet.row(R.drawable.ic_power, getString(R.string.menu_stop), cur?.name, c.danger) { stopCurrent() }
        sheet.show()
    }

    // ------------------------------------------------------------------ webview / buttons

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                if (isServerReady) {
                    setServerRunningUi(true)
                    val cur = current
                    if (cur != null && url?.startsWith("http://127.0.0.1") == true) {
                        sampleThemeColor(cur)
                        handler.postDelayed({ sampleThemeColor(cur) }, 1500)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    isServerReady = false
                    showMessage(getString(R.string.page_error))
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                val host = u.host
                if (host == "127.0.0.1" || host == "localhost") return false
                openExternal(u.toString())
                return true
            }
        }

        // files served by the script are handed to the system browser / download manager
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(url) }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView, url: String, message: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(v: WebView, url: String, message: String, r: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> r.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                } catch (_: Exception) {
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_CHOOSER) {
            val callback = fileChooserCallback ?: return
            fileChooserCallback = null
            if (resultCode != Activity.RESULT_OK || data == null) {
                callback.onReceiveValue(null)
                return
            }
            val uris: Array<Uri> = when {
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
            callback.onReceiveValue(if (uris.isEmpty()) null else uris)
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { }
    }

    /** Reload the page if the server is up, otherwise (re)start the selected script. */
    private fun refreshAction() {
        val cur = current
        if (isServerReady) webView.reload()
        else if (cur != null) launch(cur)
        else bootstrap()
    }

    private fun setupButtons() {
        btnSettings.setOnClickListener { showSettingsMenu() }

        btnStartServer.setOnClickListener {
            val cur = current
            if (scripts.isEmpty()) bootstrap()
            else if (cur != null) launch(cur)
            else showPicker()
        }

        btnRefresh.setOnClickListener { refreshAction() }
        btnTopRefresh.setOnClickListener {
            btnTopRefresh.animate().rotationBy(360f).setDuration(400).start()
            refreshAction()
        }

        btnOpenTermux.setOnClickListener {
            TermuxHelper.openTermux(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        TermuxHelper.listCallback = null
        webView.loadUrl("about:blank")
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
