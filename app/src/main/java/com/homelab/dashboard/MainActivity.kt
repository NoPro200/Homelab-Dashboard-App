package com.homelab.dashboard

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var serviceAdapter: ServiceAdapter
    private val services = mutableListOf<Service>()
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerServices: RecyclerView
    private lateinit var btnVPN: MaterialButton
    private lateinit var btnAddService: MaterialButton
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: View
    private lateinit var authOverlay: View
    private lateinit var btnUnlock: MaterialButton
    private lateinit var tvNetworkStatus: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnExpandCollapseAll: TextView
    
    private lateinit var prefsManager: PreferencesManager
    private var isAuthenticated = false
    private var wasInBackground = false
    
    // Icon type display names for spinner — first entry is "Auto" (favicon/app icon)
    private val iconTypeNames = listOf(
        "Auto (Favicon / App Icon)",
        "Proxmox", "TrueNAS", "Portainer", "Home Assistant",
        "Grafana", "Plex", "Jellyfin", "Nextcloud", "Pi-hole",
        "Docker", "Kubernetes", "Unraid", "OpenMediaVault", "Synology",
        "Nginx", "Traefik"
    )
    private val iconTypeValues = listOf(
        IconType.DEFAULT, // "Auto"
        IconType.PROXMOX, IconType.TRUENAS, IconType.PORTAINER,
        IconType.HOME_ASSISTANT, IconType.GRAFANA, IconType.PLEX, IconType.JELLYFIN,
        IconType.NEXTCLOUD, IconType.PIHOLE, IconType.DOCKER, IconType.KUBERNETES,
        IconType.UNRAID, IconType.OPENMEDIAVAULT, IconType.SYNOLOGY, IconType.NGINX,
        IconType.TRAEFIK
    )
    
    // For icon upload
    private var pendingIconServiceId: String? = null
    private var uploadedIconPath: String? = null
    private var dialogIconPreview: ImageView? = null
    
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleIconUpload(it) }
    }
    
    private val viewModeNames = listOf("Default (Global Setting)", "Mobile", "Desktop")
    private val viewModeValues = listOf("default", "mobile", "desktop")
    
    private val serviceTypeNames = listOf("Web Service (URL)", "App")
    
    // For app picker dialog
    private var selectedAppPackage: String? = null
    
    // VPN status polling
    private val vpnHandler = Handler(Looper.getMainLooper())
    private val vpnCheckRunnable = object : Runnable {
        override fun run() {
            updateVpnStatus()
            vpnHandler.postDelayed(this, 5000)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefsManager = PreferencesManager(this)
        
        // Restore auth state from rotation / config change
        if (savedInstanceState != null) {
            isAuthenticated = savedInstanceState.getBoolean("is_authenticated", false)
        }
        
        // Secure screenshots
        if (prefsManager.isSecureScreenshots()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        
        initViews()
        setupToolbar()
        setupUI()
        loadServices()
        
        // Biometric auth logic:
        // - Rotation (savedInstanceState != null): isAuthenticated is restored, no re-prompt
        // - Cold start / device restart (savedInstanceState == null): ALWAYS prompt if biometric enabled
        // - Coming back from background: handled in onResume
        if (isAuthenticated) {
            authOverlay.visibility = View.GONE
        } else if (prefsManager.isBiometricEnabled()) {
            // Cold start or device restart — always require auth
            authOverlay.visibility = View.VISIBLE
            showBiometricPrompt()
        } else {
            isAuthenticated = true
            authOverlay.visibility = View.GONE
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_authenticated", isAuthenticated)
    }
    
    // ==================== BIOMETRIC AUTH ====================
    
    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(this)
                val biometricPrompt = BiometricPrompt(this, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            isAuthenticated = true
                            prefsManager.setLastAuthTime(System.currentTimeMillis())
                            authOverlay.visibility = View.GONE
                        }
                        
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                // Stay on overlay, don't finish — let user tap Unlock again
                            }
                        }
                        
                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            Toast.makeText(this@MainActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    })
                
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Homelab Dashboard")
                    .setSubtitle("Authenticate to access your services")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                
                biometricPrompt.authenticate(promptInfo)
            }
            else -> {
                isAuthenticated = true
                authOverlay.visibility = View.GONE
            }
        }
    }
    
    // ==================== NETWORK & VPN STATUS ====================
    
    private fun isVpnActive(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    
    @Suppress("DEPRECATION")
    private fun getCurrentSsid(): String? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo.ssid?.replace("\"", "")
            if (ssid == "<unknown ssid>" || ssid.isNullOrEmpty()) null else ssid
        } catch (_: Exception) { null }
    }
    
    private fun isOnLocalNetwork(): Boolean {
        val currentSsid = getCurrentSsid() ?: return false
        val networks = prefsManager.getNetworks()
        return networks.any { network ->
            network.isLocal && network.hasSsid() && 
            network.getDisplaySsid().equals(currentSsid, ignoreCase = true)
        }
    }
    
    private fun getMatchingNetworkName(): String? {
        val currentSsid = getCurrentSsid() ?: return null
        val networks = prefsManager.getNetworks()
        return networks.firstOrNull { network ->
            network.hasSsid() && network.getDisplaySsid().equals(currentSsid, ignoreCase = true)
        }?.name
    }
    
    private fun updateVpnStatus() {
        val vpnActive = isVpnActive()
        val vpnName = if (prefsManager.hasVpnApp()) prefsManager.getVpnAppName() else "VPN"
        val onLocal = isOnLocalNetwork()
        
        // Pass network state to adapter for public URL badges
        if (::serviceAdapter.isInitialized) {
            serviceAdapter.isOnVpn = vpnActive
            serviceAdapter.isOnLocal = onLocal
        }
        val networkName = getMatchingNetworkName()
        val currentSsid = getCurrentSsid()
        
        // Update VPN status line
        if (vpnActive) {
            btnVPN.text = "$vpnName Connected"
            tvStatus.text = "$vpnName Connected"
            statusIndicator.setBackgroundResource(R.drawable.indicator_online)
        } else if (onLocal) {
            btnVPN.text = if (prefsManager.hasVpnApp()) "Open $vpnName" else getString(R.string.vpn_button)
            tvStatus.text = "Local Network — VPN not needed"
            statusIndicator.setBackgroundResource(R.drawable.indicator_online)
        } else {
            btnVPN.text = if (prefsManager.hasVpnApp()) "Open $vpnName" else getString(R.string.vpn_button)
            tvStatus.text = getString(R.string.vpn_disconnected)
            statusIndicator.setBackgroundResource(R.drawable.indicator_offline)
        }
        
        // Update network status line
        tvNetworkStatus.text = when {
            currentSsid != null && networkName != null -> "WiFi: $currentSsid ($networkName)"
            currentSsid != null -> "WiFi: $currentSsid (unknown network)"
            vpnActive -> "Connected via VPN"
            else -> "No WiFi connected"
        }
        tvNetworkStatus.setTextColor(getColor(
            if (onLocal || vpnActive) R.color.neon_green else R.color.text_tertiary
        ))
    }
    
    // ==================== VPN APP ====================
    
    private fun openVpnApp() {
        val vpnPackage = prefsManager.getVpnAppPackage()
        if (vpnPackage != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(vpnPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
            Toast.makeText(this, "VPN app not found. Please reconfigure in settings.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No VPN app configured. Set one in Settings.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    // ==================== SETUP ====================
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerServices = findViewById(R.id.recyclerServices)
        btnVPN = findViewById(R.id.btnVPN)
        btnAddService = findViewById(R.id.btnAddService)
        fabAdd = findViewById(R.id.fabAdd)
        tvStatus = findViewById(R.id.tvStatus)
        statusIndicator = findViewById(R.id.statusIndicator)
        authOverlay = findViewById(R.id.authOverlay)
        btnUnlock = findViewById(R.id.btnUnlock)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        etSearch = findViewById(R.id.etSearch)
        btnExpandCollapseAll = findViewById(R.id.btnExpandCollapseAll)
    }
    
    private fun setupUI() {
        serviceAdapter = ServiceAdapter(services, prefsManager) { service, action ->
            when (action) {
                ServiceAdapter.Action.OPEN -> openService(service)
                ServiceAdapter.Action.EDIT -> editService(service)
                ServiceAdapter.Action.DELETE -> deleteService(service)
                ServiceAdapter.Action.TOGGLE_FAVORITE -> toggleFavorite(service)
                ServiceAdapter.Action.TOGGLE_HIDDEN -> toggleHidden(service)
            }
        }
        
        recyclerServices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = serviceAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(20)
            recycledViewPool.setMaxRecycledViews(ServiceAdapter.TYPE_SERVICE, 15)
            recycledViewPool.setMaxRecycledViews(ServiceAdapter.TYPE_HEADER, 10)
        }
        
        // Save when services reordered
        serviceAdapter.onServicesReordered = {
            prefsManager.saveServices(services)
        }
        
        btnVPN.setOnClickListener { openVpnApp() }
        btnAddService.setOnClickListener { showAddServiceDialog() }
        fabAdd.setOnClickListener { showAddServiceDialog() }
        btnUnlock.setOnClickListener { showBiometricPrompt() }
        
        // Expand/Collapse All
        btnExpandCollapseAll.setOnClickListener {
            if (serviceAdapter.isAllExpanded()) {
                serviceAdapter.collapseAll()
                btnExpandCollapseAll.text = "Expand All"
            } else {
                serviceAdapter.expandAll()
                btnExpandCollapseAll.text = "Collapse All"
            }
        }
        
        // Search
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                serviceAdapter.setSearchQuery(s?.toString() ?: "")
            }
        })
    }
    
    private fun toggleFavorite(service: Service) {
        val position = services.indexOf(service)
        if (position != -1) {
            services[position] = service.copy(isFavorite = !service.isFavorite)
            prefsManager.saveServices(services)
            serviceAdapter.rebuildDisplayList()
        }
    }
    
    private fun toggleHidden(service: Service) {
        val position = services.indexOf(service)
        if (position != -1) {
            services[position] = service.copy(isHidden = !service.isHidden)
            prefsManager.saveServices(services)
            serviceAdapter.rebuildDisplayList()
            val label = if (services[position].isHidden) "hidden" else "visible"
            Toast.makeText(this, "${service.name} is now $label", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== ICON UPLOAD ====================
    
    private fun handleIconUpload(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            val serviceId = pendingIconServiceId ?: java.util.UUID.randomUUID().toString()
            val iconDir = IconCacheManager.getCustomIconDir(this)
            val iconFile = File(iconDir, "$serviceId.png")
            FileOutputStream(iconFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            uploadedIconPath = iconFile.absolutePath
            
            // Update preview in dialog if open
            dialogIconPreview?.setImageBitmap(bitmap)
            
            Toast.makeText(this, "Icon uploaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to upload icon: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== SERVICES ====================
    
    private fun loadServices() {
        services.clear()
        val savedServices = prefsManager.getServices()
        if (savedServices.isNotEmpty()) {
            services.addAll(savedServices)
        } else {
            loadDefaultServices()
        }
        serviceAdapter.rebuildDisplayList()
    }
    
    private fun loadDefaultServices() {
        services.addAll(listOf(
            Service(name = "Proxmox", url = "https://prox.mox:8006", iconType = IconType.PROXMOX, iconSource = "preset", category = "Infrastructure", isFavorite = true),
            Service(name = "TrueNAS", url = "https://truenas.local", iconType = IconType.TRUENAS, iconSource = "preset", category = "Infrastructure"),
            Service(name = "Portainer", url = "https://portainer.local:9443", iconType = IconType.PORTAINER, iconSource = "preset", category = "Infrastructure"),
            Service(name = "Home Assistant", url = "http://homeassistant.local:8123", iconType = IconType.HOME_ASSISTANT, iconSource = "preset", category = "Smart Home"),
            Service(name = "Grafana", url = "http://grafana.local:3000", iconType = IconType.GRAFANA, iconSource = "preset", category = "Monitoring")
        ))
        prefsManager.saveServices(services)
    }
    
    private fun openService(service: Service) {
        if (service.isApp && service.packageName != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(service.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "App not found: ${service.packageName}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open app: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Determine which URL to use: local/VPN -> service.url, otherwise -> publicUrl fallback
            val vpnActive = isVpnActive()
            val onLocal = isOnLocalNetwork()
            val effectiveUrl = if (!vpnActive && !onLocal && !service.publicUrl.isNullOrEmpty()) {
                service.publicUrl
            } else {
                service.url
            }
            
            val intent = Intent(this, WebViewActivity::class.java).apply {
                putExtra("service_name", service.name)
                putExtra("service_url", effectiveUrl)
                putExtra("service_id", service.id)
                putExtra("service_view_mode", service.viewMode)
                service.username?.let { putExtra("username", it) }
                service.password?.let { putExtra("password", it) }
            }
            startActivity(intent)
            
            if (!vpnActive && !onLocal && !service.publicUrl.isNullOrEmpty()) {
                Toast.makeText(this, "Opening via public URL", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun editService(service: Service) {
        showAddServiceDialog(service)
    }
    
    private fun deleteService(service: Service) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_delete))
            .setMessage(getString(R.string.confirm_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val position = services.indexOf(service)
                if (position != -1) {
                    IconCacheManager.clearCacheForService(this, service.id)
                    services.removeAt(position)
                    prefsManager.saveServices(services)
                    serviceAdapter.rebuildDisplayList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    // ==================== APP PICKER ====================
    
    @Suppress("DEPRECATION")
    private fun getInstalledApps(): List<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }
    
    private fun showAppPickerDialog(onAppSelected: (String, String) -> Unit) {
        val apps = getInstalledApps()
        val allAppNames = apps.map { it.loadLabel(packageManager).toString() }
        val allAppPackages = apps.map { it.activityInfo.packageName }
        
        // Find which packages are already added as services
        val addedPackages = services.filter { it.isApp }.mapNotNull { it.packageName }.toSet()
        
        // Build display names with ✓ for already-added
        val displayNames = allAppNames.mapIndexed { i, name ->
            if (allAppPackages[i] in addedPackages) "✓ $name" else name
        }
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val searchInput = android.widget.EditText(this).apply {
            hint = "Search apps..."
            setTextColor(resources.getColor(R.color.text_primary, null))
            setHintTextColor(resources.getColor(R.color.text_tertiary, null))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val listView = android.widget.ListView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 800
            )
        }
        container.addView(searchInput)
        container.addView(listView)
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames.toMutableList())
        listView.adapter = adapter
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Select App")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter.filter(s)
            }
        })
        
        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedDisplay = adapter.getItem(position) ?: return@setOnItemClickListener
            // Strip the ✓ prefix to find the original name
            val cleanName = selectedDisplay.removePrefix("✓ ")
            val originalIndex = allAppNames.indexOf(cleanName)
            if (originalIndex >= 0) {
                onAppSelected(allAppPackages[originalIndex], cleanName)
            }
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    // ==================== ADD/EDIT SERVICE DIALOG ====================
    
    private fun showAddServiceDialog(existingService: Service? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_service, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etServiceName)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etServiceUrl)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val etPublicUrl = dialogView.findViewById<TextInputEditText>(R.id.etPublicUrl)
        val publicUrlLayout = dialogView.findViewById<TextInputLayout>(R.id.publicUrlInputLayout)
        val etCategory = dialogView.findViewById<TextInputEditText>(R.id.etCategory)
        val switchFavorite = dialogView.findViewById<SwitchMaterial>(R.id.switchFavorite)
        val btnUploadIcon = dialogView.findViewById<MaterialButton>(R.id.btnUploadIcon)
        val usernameLayout = dialogView.findViewById<TextInputLayout>(R.id.usernameInputLayout)
        val passwordLayout = dialogView.findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val urlInputLayout = dialogView.findViewById<TextInputLayout>(R.id.urlInputLayout)
        val btnPickApp = dialogView.findViewById<MaterialButton>(R.id.btnPickApp)
        val spinnerServiceType = dialogView.findViewById<Spinner>(R.id.spinnerServiceType)
        val spinnerIcon = dialogView.findViewById<Spinner>(R.id.spinnerIcon)
        val spinnerViewMode = dialogView.findViewById<Spinner>(R.id.spinnerViewMode)
        
        selectedAppPackage = existingService?.packageName
        uploadedIconPath = existingService?.customIconPath
        pendingIconServiceId = existingService?.id ?: java.util.UUID.randomUUID().toString()
        dialogIconPreview = null // Will be set when icon is uploaded
        
        // Service Type Spinner
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, serviceTypeNames)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerServiceType.adapter = typeAdapter
        
        // Toggle URL/App picker based on type
        spinnerServiceType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val isApp = pos == 1
                urlInputLayout.visibility = if (isApp) View.GONE else View.VISIBLE
                publicUrlLayout.visibility = if (isApp) View.GONE else View.VISIBLE
                btnPickApp.visibility = if (isApp) View.VISIBLE else View.GONE
                spinnerViewMode.visibility = if (isApp) View.GONE else View.VISIBLE
                usernameLayout.visibility = if (isApp) View.GONE else View.VISIBLE
                passwordLayout.visibility = if (isApp) View.GONE else View.VISIBLE
                // Hide viewmode label too
                dialogView.findViewById<View>(R.id.spinnerViewMode)?.let { spinner ->
                    // Find the label before it
                    val parent2 = spinner.parent as? android.view.ViewGroup
                    parent2?.let { p ->
                        val idx = p.indexOfChild(spinner)
                        if (idx > 0) p.getChildAt(idx - 1)?.visibility = if (isApp) View.GONE else View.VISIBLE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Icon Spinner
        val iconAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, iconTypeNames)
        iconAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerIcon.adapter = iconAdapter
        
        // View Mode Spinner
        val viewModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, viewModeNames)
        viewModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerViewMode.adapter = viewModeAdapter
        
        // App Picker Button
        btnPickApp.setOnClickListener {
            showAppPickerDialog { pkg, label ->
                selectedAppPackage = pkg
                btnPickApp.text = label
                if (etName.text.isNullOrEmpty()) {
                    etName.setText(label)
                }
            }
        }
        
        // Upload icon button
        btnUploadIcon.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        if (existingService?.hasCustomIcon() == true) {
            btnUploadIcon.text = "Change Custom Icon"
        }
        
        // Pre-fill for editing
        existingService?.let {
            etName.setText(it.name)
            etUrl.setText(it.url)
            etUsername.setText(it.username ?: "")
            etPassword.setText(it.password ?: "")
            etPublicUrl.setText(it.publicUrl ?: "")
            etCategory.setText(if (it.category.isNullOrEmpty() || it.category == "Uncategorized") "" else it.category)
            switchFavorite.isChecked = it.isFavorite
            if (it.isApp) {
                spinnerServiceType.setSelection(1)
                btnPickApp.text = it.packageName ?: "Select App..."
            }
            // Icon: if preset, find index; if auto, index 0
            val iconIndex = if (it.getIconSourceSafe() == "preset") iconTypeValues.indexOf(it.iconType) else 0
            if (iconIndex >= 0) spinnerIcon.setSelection(iconIndex)
            val viewModeIndex = viewModeValues.indexOf(it.viewMode)
            if (viewModeIndex >= 0) spinnerViewMode.setSelection(viewModeIndex)
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val name = etName.text.toString().trim()
            val isApp = spinnerServiceType.selectedItemPosition == 1
            val url = etUrl.text.toString().trim()
            
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isApp && url.isEmpty()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isApp && selectedAppPackage == null) {
                Toast.makeText(this, "Please select an app", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val iconSpinnerPos = spinnerIcon.selectedItemPosition
            val selectedIconType = iconTypeValues.getOrElse(iconSpinnerPos) { IconType.DEFAULT }
            val selectedViewMode = viewModeValues.getOrElse(spinnerViewMode.selectedItemPosition) { "default" }
            val username = etUsername.text.toString().trim().ifEmpty { null }
            val password = etPassword.text.toString().trim().ifEmpty { null }
            val publicUrl = etPublicUrl.text.toString().trim().ifEmpty { null }
            val category = etCategory.text.toString().trim().ifEmpty { "Uncategorized" }
            val isFavorite = switchFavorite.isChecked
            
            // Determine icon source
            val iconSource = when {
                // New custom icon was uploaded this session
                uploadedIconPath != null && uploadedIconPath != existingService?.customIconPath -> "custom"
                // User chose a preset icon from dropdown
                iconSpinnerPos > 0 -> "preset"
                // Keep existing custom icon if user didn't change anything
                existingService?.hasCustomIcon() == true && uploadedIconPath == existingService.customIconPath -> "custom"
                // Default: auto (favicon/app icon)
                else -> "auto"
            }
            
            val serviceId = existingService?.id ?: pendingIconServiceId ?: java.util.UUID.randomUUID().toString()
            
            val newService = Service(
                name = name,
                url = if (isApp) "" else url,
                id = serviceId,
                iconType = selectedIconType,
                customIconPath = if (iconSource == "custom") uploadedIconPath else existingService?.customIconPath,
                viewMode = selectedViewMode,
                isApp = isApp,
                packageName = if (isApp) selectedAppPackage else null,
                username = if (isApp) null else username,
                password = if (isApp) null else password,
                category = category,
                isFavorite = isFavorite,
                publicUrl = if (isApp) null else publicUrl,
                iconSource = iconSource,
                sortOrder = existingService?.sortOrder ?: (services.size * 10),
                isHidden = existingService?.isHidden ?: false
            )
            
            if (existingService != null) {
                val position = services.indexOf(existingService)
                if (position != -1) {
                    services[position] = newService
                }
            } else {
                services.add(newService)
            }
            
            prefsManager.saveServices(services)
            serviceAdapter.rebuildDisplayList()
            dialog.dismiss()
            Toast.makeText(this, "Service saved", Toast.LENGTH_SHORT).show()
        }
        
        dialogView.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    // ==================== LIFECYCLE ====================
    
    override fun onResume() {
        super.onResume()
        
        // Check if we need to re-authenticate after coming back from background
        if (wasInBackground && prefsManager.isBiometricEnabled()) {
            val lockOnScreenOff = prefsManager.isLockOnScreenOff()
            if (lockOnScreenOff || prefsManager.needsBiometricAuth()) {
                isAuthenticated = false
                authOverlay.visibility = View.VISIBLE
                showBiometricPrompt()
            }
            wasInBackground = false
        }
        
        loadServices()
        updateVpnStatus()
        vpnHandler.post(vpnCheckRunnable)
    }
    
    override fun onStop() {
        super.onStop()
        vpnHandler.removeCallbacks(vpnCheckRunnable)
        
        // Don't treat rotation/config change as "going to background"
        if (!isChangingConfigurations) {
            wasInBackground = true
            
            // If "lock on screen off" is enabled, invalidate auth
            if (prefsManager.isLockOnScreenOff() && prefsManager.isBiometricEnabled()) {
                prefsManager.setLastAuthTime(0L)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        vpnHandler.removeCallbacks(vpnCheckRunnable)
    }
}
