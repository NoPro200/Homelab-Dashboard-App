package com.homelab.dashboard

data class Service(
    val name: String,
    val url: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
    val iconType: IconType = IconType.DEFAULT,
    val customIconPath: String? = null,
    val username: String? = null,
    val password: String? = null,
    val category: String? = "Uncategorized",
    val requiresVPN: Boolean = true,
    val sortOrder: Int = 0,
    val viewMode: String = "default",
    val isApp: Boolean = false,
    val packageName: String? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val publicUrl: String? = null, // Fallback URL when not on VPN/local network
    // Icon source: "auto" = favicon/app icon, "preset" = from IconType dropdown, "custom" = user uploaded
    val iconSource: String? = "auto"
) {
    fun getIconSourceSafe(): String = iconSource ?: "auto"
    fun hasCustomIcon(): Boolean = getIconSourceSafe() == "custom" && !customIconPath.isNullOrEmpty()
    fun hasPresetIcon(): Boolean = getIconSourceSafe() == "preset" && iconType != IconType.DEFAULT
}

data class VpnAppConfig(
    val packageName: String,
    val displayName: String
)

enum class IconType {
    DEFAULT,
    PROXMOX,
    TRUENAS,
    PORTAINER,
    HOME_ASSISTANT,
    GRAFANA,
    PLEX,
    JELLYFIN,
    NEXTCLOUD,
    PIHOLE,
    DOCKER,
    KUBERNETES,
    UNRAID,
    OPENMEDIAVAULT,
    SYNOLOGY,
    NGINX,
    TRAEFIK,
    CUSTOM
}

data class Network(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val wifiSsid: String? = null, // WLAN name to match against (nullable for Gson compat)
    val subnet: String? = null,   // Legacy field kept for Gson backward compat
    val isLocal: Boolean = true   // true = kein VPN nötig
) {
    fun getDisplaySsid(): String = wifiSsid ?: ""
    fun hasSsid(): Boolean = !wifiSsid.isNullOrEmpty()
}

// Bundle for export/import
data class ExportData(
    val version: Int = 2,
    val services: List<Service> = emptyList(),
    val networks: List<Network> = emptyList(),
    val categoryOrder: List<String> = emptyList(),
    val defaultCollapsed: List<String> = emptyList(),
    val vpnAppPackage: String? = null,
    val vpnAppName: String? = null,
    val webviewMode: String = "mobile",
    val biometricEnabled: Boolean = false,
    val biometricTimeout: Long = 0,
    val secureScreenshots: Boolean = false,
    val darkTheme: Boolean = true
)

data class ZeroTierNetwork(
    val id: String = java.util.UUID.randomUUID().toString(),
    val networkId: String,
    val name: String,
    val isConnected: Boolean = false,
    val allowManaged: Boolean = true,
    val allowGlobal: Boolean = false,
    val allowDefault: Boolean = false
)
