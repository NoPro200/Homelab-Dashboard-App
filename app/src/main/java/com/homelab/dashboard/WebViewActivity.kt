package com.homelab.dashboard

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.util.Locale

class WebViewActivity : AppCompatActivity() {
    
    private var serviceName: String = ""
    private var serviceUrl: String = ""
    private var serviceId: String = ""
    private var username: String? = null
    private var password: String? = null
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var errorView: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetry: MaterialButton
    
    private lateinit var prefsManager: PreferencesManager
    private var currentViewMode = "mobile"
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = if (result.resultCode == RESULT_OK) result.data?.data else null
        filePathCallback?.onReceiveValue(if (data != null) arrayOf(data) else null)
        filePathCallback = null
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        
        prefsManager = PreferencesManager(this)
        
        // Get intent extras
        serviceName = intent.getStringExtra("service_name") ?: "Service"
        serviceUrl = intent.getStringExtra("service_url") ?: ""
        serviceId = intent.getStringExtra("service_id") ?: ""
        username = intent.getStringExtra("username")
        password = intent.getStringExtra("password")
        
        // Determine view mode: per-service overrides global default
        val serviceViewMode = intent.getStringExtra("service_view_mode") ?: "default"
        currentViewMode = if (serviceViewMode == "default") {
            prefsManager.getDefaultWebViewMode()
        } else {
            serviceViewMode
        }
        
        initViews()
        setupToolbar()
        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        
        if (serviceUrl.isNotEmpty()) {
            loadUrl(serviceUrl)
        } else {
            showError("Invalid URL")
        }
    }
    
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        errorView = findViewById(R.id.errorView)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetry = findViewById(R.id.btnRetry)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = serviceName
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.webview_menu, menu)
        updateMenuIcon(menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_toggle_view -> {
                toggleViewMode()
                true
            }
            R.id.action_refresh -> {
                webView.reload()
                true
            }
            R.id.action_desktop_mode -> {
                setViewMode("desktop")
                true
            }
            R.id.action_mobile_mode -> {
                setViewMode("mobile")
                true
            }
            R.id.action_clear_cache -> {
                clearServiceCache()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun clearServiceCache() {
        // Clear WebView cache for this site
        webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        
        // Clear icon cache for this service
        if (serviceId.isNotEmpty()) {
            IconCacheManager.clearCacheForService(this, serviceId)
        }
        
        Toast.makeText(this, "Cache cleared for $serviceName", Toast.LENGTH_SHORT).show()
        webView.reload()
    }
    
    private fun toggleViewMode() {
        currentViewMode = if (currentViewMode == "mobile") "desktop" else "mobile"
        applyUserAgent()
        webView.reload()
        Toast.makeText(this, "${if (currentViewMode == "desktop") "Desktop" else "Mobile"} Mode", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }
    
    private fun setViewMode(mode: String) {
        currentViewMode = mode
        applyUserAgent()
        webView.reload()
        Toast.makeText(this, "${mode.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} Mode", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }
    
    private fun updateMenuIcon(menu: Menu?) {
        menu?.findItem(R.id.action_toggle_view)?.apply {
            title = if (currentViewMode == "desktop") "Switch to Mobile" else "Switch to Desktop"
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                // Core
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                
                // Viewport & Zoom
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                
                // File & Content Access
                allowFileAccess = true
                allowContentAccess = true
                
                // Mixed Content (needed for homelab http resources on https pages)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Modern web compat
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
                
                // Cache for offline/faster loading
                cacheMode = WebSettings.LOAD_DEFAULT
                
                // Text & Rendering
                textZoom = 100
                @Suppress("DEPRECATION")
                layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                
                // Cookies
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this@WebViewActivity.webView, true)
                
                applyUserAgent()
            }
            
            // Enable hardware acceleration
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Download handler
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setMimeType(mimeType)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Downloading file...")
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    request.setTitle(filename)
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this@WebViewActivity, "Downloading $filename", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@WebViewActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    errorView.visibility = View.GONE
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    
                    if (prefsManager.getAutoFillCredentials() && username != null && password != null) {
                        autoFillCredentials()
                    }
                }
                
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        showError(error?.description?.toString() ?: "Error loading page")
                    }
                }
                
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }
                
                // Handle external URL schemes (intent://, tel:, mailto:)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return if (url.startsWith("http://") || url.startsWith("https://")) {
                        false // Let WebView handle
                    } else {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) { }
                        true
                    }
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                }
                
                // Geolocation permission
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, true, false)
                }
                
                // Console messages (suppress in production)
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                
                // File upload support
                override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                    this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                    this@WebViewActivity.filePathCallback = filePathCallback
                    try {
                        val intent = fileChooserParams?.createIntent()
                        fileChooserLauncher.launch(intent)
                    } catch (_: Exception) {
                        this@WebViewActivity.filePathCallback = null
                        return false
                    }
                    return true
                }
            }
        }
    }
    
    private fun applyUserAgent() {
        val userAgent = when (currentViewMode) {
            "desktop" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            "mobile" -> "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            else -> webView.settings.userAgentString
        }
        webView.settings.userAgentString = userAgent
    }
    
    private fun autoFillCredentials() {
        val safeUsername = username?.replace("\\", "\\\\")?.replace("'", "\\'")
            ?.replace("\"", "\\\"")
            ?.replace("\n", "")?.replace("\r", "") ?: return
        val safePassword = password?.replace("\\", "\\\\")?.replace("'", "\\'")
            ?.replace("\"", "\\\"")
            ?.replace("\n", "")?.replace("\r", "") ?: return
        
        // Improved autofill: tries multiple strategies with delay
        val js = """
            (function() {
                function tryFill() {
                    // Strategy 1: Find password field first, then the input before it
                    var pwFields = document.querySelectorAll('input[type="password"]:not([hidden]):not([style*="display:none"])');
                    if (pwFields.length === 0) return false;
                    
                    var pwField = pwFields[0];
                    
                    // Strategy 2: Find username - look for visible text/email inputs
                    var allInputs = document.querySelectorAll('input:not([type="hidden"]):not([type="password"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"])');
                    var userField = null;
                    
                    // Prefer inputs near the password field
                    for (var i = 0; i < allInputs.length; i++) {
                        var inp = allInputs[i];
                        var type = (inp.type || '').toLowerCase();
                        var name = (inp.name || '').toLowerCase();
                        var id = (inp.id || '').toLowerCase();
                        var placeholder = (inp.placeholder || '').toLowerCase();
                        var autocomplete = (inp.getAttribute('autocomplete') || '').toLowerCase();
                        
                        if (type === 'email' || type === 'text' || type === '') {
                            if (name.match(/user|email|login|name|account/) ||
                                id.match(/user|email|login|name|account/) ||
                                placeholder.match(/user|email|login|name|account/) ||
                                autocomplete.match(/username|email/)) {
                                userField = inp;
                                break;
                            }
                        }
                    }
                    
                    // Fallback: just pick the last visible text/email input before password
                    if (!userField && allInputs.length > 0) {
                        userField = allInputs[allInputs.length > 1 ? allInputs.length - 1 : 0];
                        // Try to find the one closest to password in DOM
                        for (var j = allInputs.length - 1; j >= 0; j--) {
                            if (allInputs[j].getBoundingClientRect().top <= pwField.getBoundingClientRect().top) {
                                userField = allInputs[j];
                                break;
                            }
                        }
                    }
                    
                    if (!userField) return false;
                    
                    // Set values using native setter (works with React/Vue)
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                    nativeInputValueSetter.call(userField, '$safeUsername');
                    nativeInputValueSetter.call(pwField, '$safePassword');
                    
                    // Fire all relevant events
                    ['input', 'change', 'keydown', 'keyup', 'blur'].forEach(function(evt) {
                        userField.dispatchEvent(new Event(evt, { bubbles: true }));
                        pwField.dispatchEvent(new Event(evt, { bubbles: true }));
                    });
                    
                    return true;
                }
                
                // Try immediately, then retry after DOM updates
                if (!tryFill()) {
                    setTimeout(tryFill, 500);
                    setTimeout(tryFill, 1500);
                    setTimeout(tryFill, 3000);
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js, null)
    }
    
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            R.color.neon_cyan,
            R.color.neon_purple,
            R.color.neon_pink
        )
        
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }
    
    private fun loadUrl(url: String) {
        errorView.visibility = View.GONE
        webView.loadUrl(url)
    }
    
    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        errorView.visibility = View.VISIBLE
        tvErrorMessage.text = message
        
        btnRetry.setOnClickListener {
            loadUrl(serviceUrl)
        }
    }
    
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
