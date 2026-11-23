package com.vpnchain.android.model

import android.graphics.drawable.Drawable

enum class AuthMode(val id: String, val label: String) {
    MSCHAPV2("mschapv2", "MS-CHAPv2"),
    AUTO("auto", "Auto (MS-CHAPv2 / CHAP / PAP)"),
    CHAP("chap", "CHAP-MD5"),
    PAP("pap", "PAP");

    companion object {
        fun fromId(id: String): AuthMode = entries.firstOrNull { it.id == id } ?: MSCHAPV2
    }
}

enum class ProxyProtocol(val id: String, val label: String) {
    AUTO("auto", "Auto (SOCKS5 / HTTP)"),
    SOCKS5("socks5", "SOCKS5"),
    HTTP("http", "HTTP Proxy");

    companion object {
        fun fromId(id: String): ProxyProtocol = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class ExcludeType(val id: Int, val label: String) {
    SITE_OR_CIDR(0, "Site / IP / CIDR"),
    APPLICATION(1, "Application");

    companion object {
        fun fromId(id: Int): ExcludeType = entries.firstOrNull { it.id == id } ?: SITE_OR_CIDR
    }
}

enum class ExcludeRoute(val id: Int, val label: String) {
    BASE_VPN(0, "Base VPN (PPTP only)"),
    DIRECT(1, "Direct (No VPN)");

    companion object {
        fun fromId(id: Int): ExcludeRoute = entries.firstOrNull { it.id == id } ?: DIRECT
    }
}

data class ExcludeRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val type: ExcludeType = ExcludeType.SITE_OR_CIDR,
    val target: String = "",
    val displayName: String = "",
    val route: ExcludeRoute = ExcludeRoute.DIRECT
)

data class ChainConfig(
    // Base VPN (PPTP)
    val pptpServer: String = "",
    val pptpUsername: String = "",
    val pptpPassword: String = "",
    val authMode: AuthMode = AuthMode.MSCHAPV2,
    val requireMppe: Boolean = true,
    val statelessMppe: Boolean = true,
    val pptpMtu: Int = 1380,

    // 2nd VPN Proxy Client
    val secondVpnPackage: String = "",
    val secondVpnLabel: String = "",
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 10810,
    val proxyProtocol: ProxyProtocol = ProxyProtocol.AUTO,
    val waitForProxySeconds: Int = 90,

    // TUN & DNS
    val tunMtu: Int = 1280,
    val dnsServer: String = "1.1.1.1",
    val routePublicDnsViaPptp: Boolean = true,

    // Exclude / Split Tunneling
    val excludeRules: List<ExcludeRule> = listOf(
        ExcludeRule(
            enabled = false,
            type = ExcludeType.SITE_OR_CIDR,
            target = "10.0.0.0/8",
            displayName = "Corporate Intranet (10/8)",
            route = ExcludeRoute.DIRECT
        ),
        ExcludeRule(
            enabled = false,
            type = ExcludeType.SITE_OR_CIDR,
            target = "192.168.0.0/16",
            displayName = "Local Wi-Fi LAN (192.168/16)",
            route = ExcludeRoute.DIRECT
        )
    )
)

enum class ConnectionPhase(val label: String) {
    IDLE("IDLE"),
    CONNECTING_PPTP("CONNECTING PPTP"),
    WAITING_SECOND_VPN("WAITING 2ND VPN"),
    STARTING_TUNNEL("STARTING TUNNEL"),
    ACTIVE("CHAIN ACTIVE"),
    ERROR("ERROR")
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

enum class LogLevel {
    INFO, OK, WARN, ERROR, DIM
}

data class RuntimeStatus(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val pptpLocalIp: String = "",
    val pptpPeerIp: String = "",
    val publicIp: String = "",
    val statusDetail: String = "Ready to chain PPTP -> 2nd VPN"
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val icon: Drawable?
)
