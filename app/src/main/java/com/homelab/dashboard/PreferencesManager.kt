package com.homelab.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object IconCacheManager {
    
    // Thread pool for background favicon loading — avoids creating too many threads
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Track which ImageViews are loading which service to prevent stale updates
    private val loadingTags = java.util.concurrent.ConcurrentHashMap<Int, String>()
    
    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "service_icons")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    @SuppressLint("TrustAllX509TrustManager")
    private fun getTrustAllSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext
    }
    
    private fun downloadBitmap(urlString: String, trustAll: Boolean = false, timeoutMs: Int = 4000): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "image/*, */*")
            
            if (trustAll && conn is HttpsURLConnection) {
                val sslContext = getTrustAllSslContext()
                conn.sslSocketFactory = sslContext.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            
            conn.connect()
            val code = conn.responseCode
            if (code == 200) {
                val contentType = conn.contentType ?: ""
                // Reject HTML responses (some servers return error pages for missing favicons)
                if (contentType.contains("text/html")) { conn.disconnect(); return null }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.size < 20) return null // Too small to be a valid image
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else { conn.disconnect(); null }
        } catch (_: Exception) { null }
        finally { try { conn?.disconnect() } catch (_: Exception) {} }
    }
    
    fun getCustomIconDir(context: Context): File {
        val dir = File(context.filesDir, "custom_icons")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    fun loadServiceIcon(context: Context, service: Service, imageView: ImageView) {
        // Tag the view so we can detect stale updates from recycled views
        val viewId = System.identityHashCode(imageView)
        loadingTags[viewId] = service.id
        
        // Priority 1: Custom uploaded icon
        if (service.hasCustomIcon()) {
            val customFile = File(service.customIconPath!!)
            if (customFile.exists()) {
                imageView.setImageBitmap(BitmapFactory.decodeFile(customFile.absolutePath))
                imageView.clearColorFilter()
                return
            }
        }
        
        // Priority 2: Preset icon from dropdown
        if (service.hasPresetIcon()) {
            imageView.setImageResource(getPresetIconRes(service.iconType))
            imageView.setColorFilter(context.getColor(getPresetIconColor(service.iconType)))
            return
        }
        
        // Priority 3: Auto — app icon or favicon
        if (service.isApp && service.packageName != null) {
            try {
                val appIcon = context.packageManager.getApplicationIcon(service.packageName)
                imageView.setImageDrawable(appIcon)
                imageView.clearColorFilter()
                cacheDrawable(context, service.id, appIcon)
            } catch (e: PackageManager.NameNotFoundException) {
                loadCachedOrFallback(context, service, imageView)
            }
            return
        }
        
        if (service.url.isNotEmpty()) {
            // Show cache immediately
            val cacheFile = File(getCacheDir(context), "${service.id}.png")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                imageView.setImageBitmap(BitmapFactory.decodeFile(cacheFile.absolutePath))
                imageView.clearColorFilter()
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_compass)
                imageView.setColorFilter(context.getColor(R.color.neon_cyan))
            }
            
            // Background fetch via thread pool
            val serviceId = service.id
            val serviceUrl = service.url
            executor.submit {
                val bitmap = tryFetchFavicon(serviceUrl)
                if (bitmap != null) {
                    try {
                        val file = File(getCacheDir(context), "$serviceId.png")
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                        }
                    } catch (_: Exception) { }
                    mainHandler.post {
                        // Only update if this ImageView is still showing this service
                        if (loadingTags[viewId] == serviceId) {
                            imageView.setImageBitmap(bitmap)
                            imageView.clearColorFilter()
                        }
                    }
                }
            }
        } else {
            loadCachedOrFallback(context, service, imageView)
        }
    }
    
    private fun getPresetIconRes(iconType: IconType): Int = when (iconType) {
        IconType.PROXMOX -> android.R.drawable.ic_menu_manage
        IconType.TRUENAS -> android.R.drawable.ic_menu_save
        IconType.PORTAINER -> android.R.drawable.ic_menu_view
        IconType.HOME_ASSISTANT -> android.R.drawable.ic_menu_mylocation
        IconType.GRAFANA -> android.R.drawable.ic_menu_gallery
        IconType.PLEX -> android.R.drawable.ic_menu_slideshow
        IconType.JELLYFIN -> android.R.drawable.ic_menu_slideshow
        IconType.NEXTCLOUD -> android.R.drawable.ic_menu_upload
        IconType.PIHOLE -> android.R.drawable.ic_menu_close_clear_cancel
        IconType.DOCKER -> android.R.drawable.ic_menu_sort_by_size
        IconType.KUBERNETES -> android.R.drawable.ic_menu_sort_by_size
        else -> android.R.drawable.ic_menu_compass
    }
    
    private fun getPresetIconColor(iconType: IconType): Int = when (iconType) {
        IconType.PROXMOX -> R.color.icon_proxmox
        IconType.TRUENAS -> R.color.icon_truenas
        IconType.PORTAINER -> R.color.icon_portainer
        IconType.HOME_ASSISTANT -> R.color.icon_home_assistant
        IconType.GRAFANA -> R.color.icon_grafana
        IconType.PLEX -> R.color.icon_plex
        IconType.JELLYFIN -> R.color.icon_jellyfin
        IconType.NEXTCLOUD -> R.color.icon_nextcloud
        IconType.PIHOLE -> R.color.icon_pihole
        IconType.DOCKER -> R.color.icon_docker
        IconType.KUBERNETES -> R.color.icon_kubernetes
        else -> R.color.neon_cyan
    }
    
    private fun tryFetchFavicon(serviceUrl: String): Bitmap? {
        val uri = android.net.Uri.parse(serviceUrl)
        val host = uri.host ?: return null
        val scheme = uri.scheme ?: "https"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val baseUrl = "$scheme://$host$port"
        val isLocal = host.endsWith(".local") || !host.contains(".") || 
                      host.matches(Regex("^(192\\.168|10\\.|172\\.(1[6-9]|2\\d|3[01])).*"))
        
        // Strategy 1: Direct favicon.ico (with SSL trust)
        downloadBitmap("$baseUrl/favicon.ico", trustAll = true)?.let { return it }
        
        // Strategy 2: Try to parse HTML <link rel="icon"> from the page
        try {
            val pageUrl = URL(serviceUrl)
            val conn = pageUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn is HttpsURLConnection) {
                val ctx = getTrustAllSslContext()
                conn.sslSocketFactory = ctx.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.connect()
            if (conn.responseCode == 200) {
                // Read first 8KB to find icon link
                val head = conn.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192); val n = r.read(buf); if (n > 0) String(buf, 0, n) else ""
                }
                conn.disconnect()
                val iconHref = Regex("""<link[^>]*rel=["'](?:shortcut )?icon["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(head)?.groupValues?.get(1)
                if (iconHref != null) {
                    val iconUrl = when {
                        iconHref.startsWith("http") -> iconHref
                        iconHref.startsWith("//") -> "$scheme:$iconHref"
                        iconHref.startsWith("/") -> "$baseUrl$iconHref"
                        else -> "$baseUrl/$iconHref"
                    }
                    downloadBitmap(iconUrl, trustAll = true)?.let { return it }
                }
            } else { conn.disconnect() }
        } catch (_: Exception) { }
        
        // Strategy 3: Direct favicon.png
        downloadBitmap("$baseUrl/favicon.png", trustAll = true)?.let { return it }
        
        // Strategy 4: Google Favicon API (only public domains)
        if (!isLocal) {
            downloadBitmap("https://www.google.com/s2/favicons?sz=64&domain=$host")?.let { return it }
        }
        
        // Strategy 5: DuckDuckGo Favicon API
        downloadBitmap("https://icons.duckduckgo.com/ip3/$host.ico")?.let { return it }
        
        return null
    }
    
    private fun loadCachedOrFallback(context: Context, service: Service, imageView: ImageView) {
        val cacheFile = File(getCacheDir(context), "${service.id}.png")
        if (cacheFile.exists()) {
            imageView.setImageBitmap(BitmapFactory.decodeFile(cacheFile.absolutePath))
            imageView.clearColorFilter()
        } else {
            imageView.setImageResource(android.R.drawable.ic_menu_compass)
            imageView.setColorFilter(context.getColor(R.color.neon_cyan))
        }
    }
    
    private fun cacheDrawable(context: Context, serviceId: String, drawable: Drawable) {
        try {
            val bitmap = drawableToBitmap(drawable)
            val file = File(getCacheDir(context), "$serviceId.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) { }
    }
    
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
    
    fun clearCacheForService(context: Context, serviceId: String) {
        try {
            val file = File(getCacheDir(context), "$serviceId.png")
            if (file.exists()) file.delete()
        } catch (_: Exception) { }
    }
    
    fun clearAllCache(context: Context) {
        try {
            getCacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
    }
}

class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("homelab_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Encrypted preferences for credentials
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
    }
    
    // Services
    fun saveServices(services: List<Service>) {
        val json = gson.toJson(services)
        prefs.edit().putString("services", json).apply()
    }
    
    fun getServices(): List<Service> {
        val json = prefs.getString("services", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Service>>() {}.type
            gson.fromJson<List<Service>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Networks
    fun saveNetworks(networks: List<Network>) {
        val json = gson.toJson(networks)
        prefs.edit().putString("networks", json).apply()
    }
    
    fun getNetworks(): List<Network> {
        val json = prefs.getString("networks", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Network>>() {}.type
            val list: List<Network> = gson.fromJson(json, type)
            list.map { n ->
                // Ensure no nulls from Gson on non-null-looking fields
                n.copy(name = n.name ?: "Unknown")
            }
        } catch (e: Exception) {
            // Old data format — clear and return empty
            prefs.edit().remove("networks").apply()
            emptyList()
        }
    }
    
    // ZeroTier Networks
    fun saveZeroTierNetworks(networks: List<ZeroTierNetwork>) {
        val json = gson.toJson(networks)
        encryptedPrefs.edit().putString("zt_networks", json).apply()
    }
    
    fun getZeroTierNetworks(): List<ZeroTierNetwork> {
        val json = encryptedPrefs.getString("zt_networks", null) ?: return emptyList()
        val type = object : TypeToken<List<ZeroTierNetwork>>() {}.type
        return gson.fromJson(json, type)
    }
    
    // VPN Status
    fun setVpnConnected(connected: Boolean) {
        prefs.edit().putBoolean("vpn_connected", connected).apply()
    }
    
    fun isVpnConnected(): Boolean {
        return prefs.getBoolean("vpn_connected", false)
    }
    
    // Auto-connect VPN
    fun setAutoConnectVPN(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect_vpn", enabled).apply()
    }
    
    fun shouldAutoConnectVPN(): Boolean {
        return prefs.getBoolean("auto_connect_vpn", false)
    }
    
    // WebView Mode
    fun setDefaultWebViewMode(mode: String) {
        prefs.edit().putString("webview_mode", mode).apply()
    }
    
    fun getDefaultWebViewMode(): String {
        return prefs.getString("webview_mode", "mobile") ?: "mobile"
    }
    
    // Show VPN Badge
    fun setShowVpnBadge(show: Boolean) {
        prefs.edit().putBoolean("show_vpn_badge", show).apply()
    }
    
    fun getShowVpnBadge(): Boolean {
        return prefs.getBoolean("show_vpn_badge", true)
    }
    
    // Auto-fill Credentials
    fun setAutoFillCredentials(enabled: Boolean) {
        prefs.edit().putBoolean("auto_fill_credentials", enabled).apply()
    }
    
    fun getAutoFillCredentials(): Boolean {
        return prefs.getBoolean("auto_fill_credentials", true)
    }
    
    // Current Network Detection
    fun setCurrentNetwork(subnet: String) {
        prefs.edit().putString("current_network", subnet).apply()
    }
    
    fun getCurrentNetwork(): String? {
        return prefs.getString("current_network", null)
    }
    
    // First Launch
    fun isFirstLaunch(): Boolean {
        val first = prefs.getBoolean("first_launch", true)
        if (first) {
            prefs.edit().putBoolean("first_launch", false).apply()
        }
        return first
    }
    
    // Theme
    fun setTheme(isDark: Boolean) {
        prefs.edit().putBoolean("dark_theme", isDark).apply()
    }
    
    fun isDarkTheme(): Boolean {
        return prefs.getBoolean("dark_theme", true)
    }
    
    // JavaScript Enabled
    fun setJavaScriptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("javascript_enabled", enabled).apply()
    }
    
    fun isJavaScriptEnabled(): Boolean {
        return prefs.getBoolean("javascript_enabled", true)
    }
    
    // Allow Cookies
    fun setAllowCookies(allow: Boolean) {
        prefs.edit().putBoolean("allow_cookies", allow).apply()
    }
    
    fun allowCookies(): Boolean {
        return prefs.getBoolean("allow_cookies", true)
    }
    
    // Icon Style
    fun setIconStyle(style: String) {
        prefs.edit().putString("icon_style", style).apply()
    }
    
    fun getIconStyle(): String {
        return prefs.getString("icon_style", "modern") ?: "modern"
    }
    
    // Biometric Auth
    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_auth", enabled).apply()
    }
    
    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean("biometric_auth", false)
    }
    
    // Secure Screenshots
    fun setSecureScreenshots(enabled: Boolean) {
        prefs.edit().putBoolean("secure_screenshots", enabled).apply()
    }
    
    fun isSecureScreenshots(): Boolean {
        return prefs.getBoolean("secure_screenshots", false)
    }
    
    // Per-service view mode
    fun setServiceViewMode(serviceId: String, mode: String) {
        prefs.edit().putString("viewmode_$serviceId", mode).apply()
    }
    
    fun getServiceViewMode(serviceId: String): String {
        return prefs.getString("viewmode_$serviceId", "default") ?: "default"
    }
    
    // Biometric timeout (milliseconds, 0 = always)
    fun setBiometricTimeout(timeoutMs: Long) {
        prefs.edit().putLong("biometric_timeout", timeoutMs).apply()
    }
    
    fun getBiometricTimeout(): Long {
        return prefs.getLong("biometric_timeout", 0L) // 0 = always ask
    }
    
    // Last successful biometric auth timestamp
    fun setLastAuthTime(timestamp: Long) {
        prefs.edit().putLong("last_auth_time", timestamp).apply()
    }
    
    fun getLastAuthTime(): Long {
        return prefs.getLong("last_auth_time", 0L)
    }
    
    fun needsBiometricAuth(): Boolean {
        if (!isBiometricEnabled()) return false
        val timeout = getBiometricTimeout()
        if (timeout == 0L) return true // always ask
        if (timeout == -1L) {
            // "When leaving app" — only triggers via invalidation in onStop
            val lastAuth = getLastAuthTime()
            return lastAuth == 0L
        }
        val lastAuth = getLastAuthTime()
        if (lastAuth == 0L) return true
        val elapsed = System.currentTimeMillis() - lastAuth
        return elapsed > timeout
    }
    
    // -1 = lock when leaving app / screen off
    fun isLockOnScreenOff(): Boolean {
        return getBiometricTimeout() == -1L
    }
    
    // VPN App Config
    fun setVpnApp(packageName: String, displayName: String) {
        prefs.edit()
            .putString("vpn_app_package", packageName)
            .putString("vpn_app_name", displayName)
            .apply()
    }
    
    fun getVpnAppPackage(): String? {
        return prefs.getString("vpn_app_package", null)
    }
    
    fun getVpnAppName(): String {
        return prefs.getString("vpn_app_name", "VPN App") ?: "VPN App"
    }
    
    fun hasVpnApp(): Boolean {
        return prefs.getString("vpn_app_package", null) != null
    }
    
    // Category order (list of category names in display order)
    fun saveCategoryOrder(order: List<String>) {
        prefs.edit().putString("category_order", gson.toJson(order)).apply()
    }
    
    fun getCategoryOrder(): List<String> {
        val json = prefs.getString("category_order", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
    
    // Default collapsed categories
    fun saveDefaultCollapsed(categories: Set<String>) {
        prefs.edit().putStringSet("default_collapsed", categories).apply()
    }
    
    fun getDefaultCollapsed(): Set<String> {
        return prefs.getStringSet("default_collapsed", emptySet()) ?: emptySet()
    }
    
    // ==================== EXPORT / IMPORT ====================
    
    fun buildExportData(): ExportData {
        return ExportData(
            version = 2,
            services = getServices(),
            networks = getNetworks(),
            categoryOrder = getCategoryOrder(),
            defaultCollapsed = getDefaultCollapsed().toList(),
            vpnAppPackage = getVpnAppPackage(),
            vpnAppName = if (hasVpnApp()) getVpnAppName() else null,
            webviewMode = getDefaultWebViewMode(),
            biometricEnabled = isBiometricEnabled(),
            biometricTimeout = getBiometricTimeout(),
            secureScreenshots = isSecureScreenshots(),
            darkTheme = isDarkTheme()
        )
    }
    
    fun exportToJson(): String {
        return gson.toJson(buildExportData())
    }
    
    fun importFromJson(json: String): Boolean {
        return try {
            val data = gson.fromJson(json, ExportData::class.java) ?: return false
            
            // Import services
            if (data.services.isNotEmpty()) {
                saveServices(data.services)
            }
            
            // Import networks
            if (data.networks.isNotEmpty()) {
                saveNetworks(data.networks)
            }
            
            // Import category order
            if (data.categoryOrder.isNotEmpty()) {
                saveCategoryOrder(data.categoryOrder)
            }
            
            // Import default collapsed
            if (data.defaultCollapsed.isNotEmpty()) {
                saveDefaultCollapsed(data.defaultCollapsed.toSet())
            }
            
            // Import VPN config
            if (data.vpnAppPackage != null && data.vpnAppName != null) {
                setVpnApp(data.vpnAppPackage, data.vpnAppName)
            }
            
            // Import settings
            setDefaultWebViewMode(data.webviewMode)
            setBiometricEnabled(data.biometricEnabled)
            setBiometricTimeout(data.biometricTimeout)
            setSecureScreenshots(data.secureScreenshots)
            setTheme(data.darkTheme)
            
            true
        } catch (e: Exception) {
            false
        }
    }
}
