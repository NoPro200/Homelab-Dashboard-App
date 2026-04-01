package com.homelab.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        
        private lateinit var prefsManager: PreferencesManager
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            prefsManager = PreferencesManager(requireContext())
            
            setupPreferences()
        }
        
        // Location permission for SSID
        private val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            if (fineGranted) {
                updateNetworkInfo()
            }
        }
        
        private fun setupPreferences() {
            // Request location permission for SSID
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                locationPermissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
            
            // Network Management
            findPreference<Preference>("network_management")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NetworkManagementActivity::class.java))
                true
            }
            
            // VPN App Selection
            findPreference<Preference>("vpn_app_select")?.apply {
                // Show current VPN app name
                if (prefsManager.hasVpnApp()) {
                    summary = prefsManager.getVpnAppName()
                } else {
                    summary = "Not configured - tap to select"
                }
                
                setOnPreferenceClickListener {
                    showVpnAppPicker()
                    true
                }
            }
            
            // Default WebView Mode
            findPreference<ListPreference>("webview_mode")?.apply {
                value = prefsManager.getDefaultWebViewMode()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setDefaultWebViewMode(newValue as String)
                    true
                }
            }
            
            // Theme
            findPreference<SwitchPreferenceCompat>("dark_theme")?.apply {
                isChecked = prefsManager.isDarkTheme()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setTheme(newValue as Boolean)
                    requireActivity().recreate()
                    true
                }
            }
            
            // Show VPN Badge
            findPreference<SwitchPreferenceCompat>("show_vpn_badge")?.apply {
                isChecked = prefsManager.getShowVpnBadge()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setShowVpnBadge(newValue as Boolean)
                    true
                }
            }
            
            // Auto-fill Credentials
            findPreference<SwitchPreferenceCompat>("auto_fill_credentials")?.apply {
                isChecked = prefsManager.getAutoFillCredentials()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setAutoFillCredentials(newValue as Boolean)
                    true
                }
            }
            
            // Clear Cache
            findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
                clearWebViewCache()
                true
            }
            
            // Biometric Authentication
            findPreference<SwitchPreferenceCompat>("biometric_auth")?.apply {
                isChecked = prefsManager.isBiometricEnabled()
                
                val biometricManager = BiometricManager.from(requireContext())
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                isEnabled = canAuth == BiometricManager.BIOMETRIC_SUCCESS
                if (!isEnabled) {
                    summary = "Not available on this device"
                }
                
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setBiometricEnabled(newValue as Boolean)
                    true
                }
            }
            
            // Biometric Timeout
            findPreference<ListPreference>("biometric_timeout")?.apply {
                value = prefsManager.getBiometricTimeout().toString()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setBiometricTimeout((newValue as String).toLongOrNull() ?: 0L)
                    true
                }
            }
            
            // Secure Screenshots
            findPreference<SwitchPreferenceCompat>("secure_screenshots")?.apply {
                isChecked = prefsManager.isSecureScreenshots()
                setOnPreferenceChangeListener { _, newValue ->
                    prefsManager.setSecureScreenshots(newValue as Boolean)
                    Toast.makeText(requireContext(), "Restart app for this to take effect", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            
            // Export Settings
            findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
                exportSettings()
                true
            }
            
            // Import Settings
            findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
                importSettings()
                true
            }
            
            // About
            findPreference<Preference>("about")?.apply {
                summary = try {
                    val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        requireContext().packageManager.getPackageInfo(requireContext().packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                    }
                    "Version ${pInfo.versionName}"
                } catch (e: Exception) {
                    "Version unknown"
                }
            }
            
            // Current Network Info
            updateNetworkInfo()
        }
        
        @Suppress("DEPRECATION")
        private fun updateNetworkInfo() {
            findPreference<Preference>("current_network")?.apply {
                val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                
                summary = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                        try {
                            val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val wifiInfo = wifiManager.connectionInfo
                            "WiFi: ${wifiInfo.ssid?.replace("\"", "") ?: "Unknown"}"
                        } catch (e: Exception) {
                            "WiFi: Connected"
                        }
                    }
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN Connected"
                    else -> "Not connected"
                }
            }
        }
        
        @Suppress("DEPRECATION")
        private fun showVpnAppPicker() {
            val pm = requireContext().packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, 0)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            
            val allAppNames = apps.map { it.loadLabel(pm).toString() }
            val allAppPackages = apps.map { it.activityInfo.packageName }
            
            val container = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 0)
            }
            val searchInput = android.widget.EditText(requireContext()).apply {
                hint = "Search apps..."
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            val listView = android.widget.ListView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 800
                )
            }
            container.addView(searchInput)
            container.addView(listView)
            
            val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, allAppNames.toMutableList())
            listView.adapter = adapter
            
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select VPN App")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .create()
            
            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { adapter.filter.filter(s) }
            })
            
            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
                val originalIndex = allAppNames.indexOf(selectedName)
                if (originalIndex < 0) return@setOnItemClickListener
                val pkg = allAppPackages[originalIndex]
                dialog.dismiss()
                
                val input = android.widget.EditText(requireContext()).apply {
                    setText(selectedName)
                    hint = "VPN Display Name"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("VPN Name")
                    .setMessage("Enter a display name for your VPN")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val name = input.text.toString().trim().ifEmpty { selectedName }
                        prefsManager.setVpnApp(pkg, name)
                        findPreference<Preference>("vpn_app_select")?.summary = name
                        Toast.makeText(requireContext(), "VPN app set to $name", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            
            dialog.show()
        }
        
        // ==================== EXPORT ====================
        
        private val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            uri?.let { writeExportFile(it) }
        }
        
        private fun exportSettings() {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
            exportLauncher.launch("homelab_backup_$timestamp.json")
        }
        
        private fun writeExportFile(uri: Uri) {
            try {
                val json = prefsManager.exportToJson()
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                }
                Toast.makeText(requireContext(), "Settings exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // ==================== IMPORT ====================
        
        private val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let { readImportFile(it) }
        }
        
        private fun importSettings() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import Settings")
                .setMessage("This will overwrite all current services, networks, and settings. Are you sure?")
                .setPositiveButton("Import") { _, _ ->
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        private fun readImportFile(uri: Uri) {
            try {
                val json = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: return
                
                val success = prefsManager.importFromJson(json)
                if (success) {
                    Toast.makeText(requireContext(), "Settings imported! Restart the app.", Toast.LENGTH_LONG).show()
                    // Recreate to apply
                    requireActivity().finishAffinity()
                } else {
                    Toast.makeText(requireContext(), "Import failed: invalid file format", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // ==================== CACHE ====================
        
        private fun clearWebViewCache() {
            try {
                requireContext().cacheDir.deleteRecursively()
                IconCacheManager.clearAllCache(requireContext())
                android.webkit.WebView(requireContext()).clearCache(true)
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                Toast.makeText(requireContext(), "All caches cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error clearing cache", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
