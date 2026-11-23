package com.vpnchain.android.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress

data class CmdResult(val exitCode: Int, val output: String)

data class PhysicalRouteInfo(
    val dev: String,
    val gateway: String?,
    val table: String
)

object RootRouter {
    const val PPTP_TABLE = "101"
    const val PPTP_IFACE = "pptp0"

    // Dedicated priority band (4800..5200) sits cleanly between local (0) and Android netd (10000+)
    private const val PREF_PPTP_SERVER = 4800
    private const val PREF_DIRECT_DST = 4900
    private const val PREF_BASE_DST = 5000
    private const val PREF_PPTP_DNS = 5050
    private const val PREF_SECOND_VPN_UID = 5100
    private const val PREF_BASE_APP_UID = 5110

    fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    fun runRoot(cmd: String): CmdResult {
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText().trim() }
            val code = proc.waitFor()
            CmdResult(code, out)
        } catch (e: Exception) {
            CmdResult(-1, e.message ?: "su failed")
        }
    }

    fun checkRootAccess(): Boolean {
        val res = runRoot("id -u")
        return res.exitCode == 0 && res.output.trim() == "0"
    }

    fun resolveIpv4(hostOrIp: String): List<String> {
        val clean = hostOrIp.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
        if (clean.isEmpty()) return emptyList()

        return try {
            InetAddress.getAllByName(clean)
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { !it.startsWith("127.") && !it.startsWith("198.18.") && !it.startsWith("198.19.") }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun detectPhysicalRoute(targetIp: String): PhysicalRouteInfo? {
        val res = runRoot("ip route get $targetIp")
        if (res.exitCode != 0 || res.output.isBlank()) return null

        val line = res.output.lineSequence().firstOrNull() ?: return null
        val tokens = line.trim().split(Regex("\\s+"))
        var dev: String? = null
        var via: String? = null
        var table: String? = null

        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "dev" -> if (i + 1 < tokens.size) dev = tokens[i + 1]
                "via" -> if (i + 1 < tokens.size) via = tokens[i + 1]
                "table" -> if (i + 1 < tokens.size) table = tokens[i + 1]
            }
            i++
        }
        val finalDev = dev ?: return null
        val finalTable = table ?: detectTableForDev(finalDev)
        return PhysicalRouteInfo(dev = finalDev, gateway = via, table = finalTable)
    }

    private fun detectTableForDev(dev: String): String {
        val rules = runRoot("ip rule show").output
        val regex = Regex("lookup\\s+([a-zA-Z0-9_.-]+)")
        for (line in rules.lineSequence()) {
            if (line.contains("oif $dev") || line.contains("lookup $dev")) {
                val m = regex.find(line)
                if (m != null) return m.groupValues[1]
            }
        }
        return "main"
    }

    fun setupPptpInterfaceAndTable(
        ifName: String,
        localIp: String,
        peerIp: String,
        mtu: Int,
        pptpServerIp: String,
        phys: PhysicalRouteInfo
    ): CmdResult {
        val serverVia = if (!phys.gateway.isNullOrBlank()) {
            "via ${phys.gateway} dev ${phys.dev}"
        } else {
            "dev ${phys.dev}"
        }

        val script = buildString {
            appendLine("ip addr flush dev $ifName 2>/dev/null || true")
            appendLine("ip addr add $localIp/32 peer $peerIp dev $ifName")
            appendLine("ip link set $ifName mtu $mtu up")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true")
            appendLine("echo 0 > /proc/sys/net/ipv4/conf/$ifName/rp_filter 2>/dev/null || true")
            appendLine("ip route flush table $PPTP_TABLE 2>/dev/null || true")
            appendLine("ip route replace $pptpServerIp/32 $serverVia table $PPTP_TABLE")
            appendLine("ip route replace default dev $ifName table $PPTP_TABLE")
            // Pin PPTP server IP to physical network above all VPN rules
            appendLine("ip rule add to $pptpServerIp/32 lookup ${phys.table} pref $PREF_PPTP_SERVER")
            // Clamp TCP MSS on pptp0 to avoid double-encapsulation TLS/handshake stalls
            appendLine("iptables -t mangle -D POSTROUTING -o $ifName -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            appendLine("iptables -t mangle -A POSTROUTING -o $ifName -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu")
        }
        return runRoot(script)
    }

    fun addUidToPptp(uid: Int, isSecondVpn: Boolean): CmdResult {
        val pref = if (isSecondVpn) PREF_SECOND_VPN_UID else PREF_BASE_APP_UID
        return runRoot("ip rule add uidrange $uid-$uid lookup $PPTP_TABLE pref $pref")
    }

    fun addDestinationToPptp(cidrOrIp: String, isDns: Boolean = false): CmdResult {
        val dst = if (cidrOrIp.contains("/")) cidrOrIp else "$cidrOrIp/32"
        val pref = if (isDns) PREF_PPTP_DNS else PREF_BASE_DST
        return runRoot("ip rule add to $dst lookup $PPTP_TABLE pref $pref")
    }

    fun addDestinationDirect(cidrOrIp: String, physTable: String): CmdResult {
        val dst = if (cidrOrIp.contains("/")) cidrOrIp else "$cidrOrIp/32"
        return runRoot("ip rule add to $dst lookup $physTable pref $PREF_DIRECT_DST")
    }

    fun cleanupAllRules(): CmdResult {
        // Delete every ip rule in our managed priority band (4800..5200) and flush table 101
        val script = buildString {
            appendLine("for p in $PREF_PPTP_SERVER $PREF_DIRECT_DST $PREF_BASE_DST $PREF_PPTP_DNS $PREF_SECOND_VPN_UID $PREF_BASE_APP_UID; do")
            appendLine("  while ip rule del pref \$p 2>/dev/null; do :; done")
            appendLine("done")
            appendLine("ip route flush table $PPTP_TABLE 2>/dev/null || true")
            appendLine("iptables -t mangle -D POSTROUTING -o $PPTP_IFACE -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true")
            appendLine("pkill -f 'libchaincore.so pptp' 2>/dev/null || true")
        }
        return runRoot(script)
    }

    fun dumpDiagnostics(): String {
        val r1 = runRoot("ip rule show").output
        val r2 = runRoot("ip route show table $PPTP_TABLE").output
        val r3 = runRoot("ip addr show dev $PPTP_IFACE 2>/dev/null || echo 'pptp0 down'").output
        return "--- ip rule ---\n$r1\n\n--- table $PPTP_TABLE ---\n$r2\n\n--- $PPTP_IFACE ---\n$r3"
    }
}
