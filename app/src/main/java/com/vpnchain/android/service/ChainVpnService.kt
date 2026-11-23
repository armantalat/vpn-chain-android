package com.vpnchain.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vpnchain.android.MainActivity
import com.vpnchain.android.R
import com.vpnchain.android.core.PhysicalRouteInfo
import com.vpnchain.android.core.RootRouter
import com.vpnchain.android.data.ConfigStore
import com.vpnchain.android.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class ChainVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chainJob: Job? = null

    @Volatile private var pptpProc: Process? = null
    @Volatile private var bridgeProc: Process? = null
    @Volatile private var tunPfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopChainAndService()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundSafe("Starting VPN Chain...")
                chainJob?.cancel()
                chainJob = serviceScope.launch {
                    runChainPipeline()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VPN Chain Status", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ChainVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("VPN Chain")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        try {
            startForegroundSafe(text)
        } catch (_: Exception) {
        }
    }

    private suspend fun runChainPipeline() {
        val cfg = ConfigStore(this).load()
        try {
            // 1. Validate config & binaries
            if (cfg.pptpServer.isBlank() || cfg.pptpUsername.isBlank()) {
                failChain("Please enter PPTP server and username first.")
                return
            }
            if (cfg.secondVpnPackage.isBlank()) {
                failChain("Please pick your 2nd VPN app first.")
                return
            }

            val nativeDir = applicationInfo.nativeLibraryDir
            val chainCoreBin = File(nativeDir, "libchaincore.so")
            val tun2socksBin = File(nativeDir, "libtun2socks.so")
            if (!chainCoreBin.exists() || !tun2socksBin.exists()) {
                failChain("Native binaries missing in $nativeDir. Build APK with Go core enabled.")
                return
            }

            ChainRuntimeBus.setPhase(ConnectionPhase.CONNECTING_PPTP, "Checking root & physical route...")
            ChainRuntimeBus.log(LogLevel.INFO, "=== Starting Android VPN Chain ===")

            if (!RootRouter.checkRootAccess()) {
                failChain("Root access denied. Grant superuser permission in Magisk / KernelSU.")
                return
            }

            // Clean any stale rules from previous sessions
            RootRouter.cleanupAllRules()

            // Resolve PPTP server while still on physical Wi-Fi / Mobile network
            ChainRuntimeBus.log(LogLevel.INFO, "Resolving PPTP server: ${cfg.pptpServer}")
            val pptpIps = RootRouter.resolveIpv4(cfg.pptpServer)
            if (pptpIps.isEmpty()) {
                failChain("Could not resolve IPv4 address for PPTP server '${cfg.pptpServer}'.")
                return
            }
            val pptpServerIp = pptpIps.first()
            ChainRuntimeBus.log(LogLevel.DIM, "PPTP server IP: $pptpServerIp")

            val phys: PhysicalRouteInfo = RootRouter.detectPhysicalRoute(pptpServerIp)
                ?: run {
                    failChain("Could not detect active Wi-Fi / Mobile route to $pptpServerIp.")
                    return
                }
            ChainRuntimeBus.log(
                LogLevel.INFO,
                "Physical uplink: dev=${phys.dev}, gw=${phys.gateway ?: "direct"}, table=${phys.table}"
            )

            // Resolve 2nd VPN app UID
            val secondVpnUid = getPackageUidSafe(cfg.secondVpnPackage)
            if (secondVpnUid < 0) {
                failChain("2nd VPN app '${cfg.secondVpnPackage}' is not installed.")
                return
            }
            ChainRuntimeBus.log(LogLevel.DIM, "2nd VPN app: ${cfg.secondVpnLabel} (${cfg.secondVpnPackage}, UID=$secondVpnUid)")

            // 2. Start userspace PPTP client as root
            ChainRuntimeBus.setPhase(ConnectionPhase.CONNECTING_PPTP, "Dialing PPTP (${cfg.authMode.label})...")
            updateNotification("Connecting PPTP (${cfg.pptpServer})...")

            val pptpCmd = buildString {
                append(RootRouter.shellQuote(chainCoreBin.absolutePath))
                append(" pptp")
                append(" -server ").append(RootRouter.shellQuote(pptpServerIp))
                append(" -user ").append(RootRouter.shellQuote(cfg.pptpUsername))
                append(" -pass ").append(RootRouter.shellQuote(cfg.pptpPassword))
                append(" -auth ").append(RootRouter.shellQuote(cfg.authMode.id))
                append(" -mppe=").append(cfg.requireMppe)
                append(" -stateless=").append(cfg.statelessMppe)
                append(" -ifname ").append(RootRouter.PPTP_IFACE)
                append(" -mtu ").append(cfg.pptpMtu)
            }

            val proc = ProcessBuilder("su", "-c", pptpCmd)
                .redirectErrorStream(true)
                .start()
            pptpProc = proc

            val upSignal = CompletableDeferred<JSONObject?>()
            val readerJob = serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            val text = line!!.trim()
                            if (text.startsWith("{")) {
                                try {
                                    val ev = JSONObject(text)
                                    val type = ev.optString("event")
                                    val msg = ev.optString("message")
                                    when (type) {
                                        "log" -> ChainRuntimeBus.log(LogLevel.DIM, "[PPTP] $msg")
                                        "up" -> {
                                            ChainRuntimeBus.log(LogLevel.OK, "[PPTP] $msg")
                                            if (!upSignal.isCompleted) upSignal.complete(ev)
                                        }
                                        "error" -> {
                                            ChainRuntimeBus.log(LogLevel.ERROR, "[PPTP] $msg")
                                            if (!upSignal.isCompleted) upSignal.complete(null)
                                        }
                                        "down" -> {
                                            ChainRuntimeBus.log(LogLevel.WARN, "[PPTP] $msg")
                                            if (!upSignal.isCompleted) upSignal.complete(null)
                                        }
                                    }
                                } catch (_: Exception) {
                                    ChainRuntimeBus.log(LogLevel.DIM, "[PPTP] $text")
                                }
                            } else if (text.isNotEmpty()) {
                                ChainRuntimeBus.log(LogLevel.DIM, "[PPTP] $text")
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!upSignal.isCompleted) upSignal.complete(null)
                }
            }

            val upEvent = withTimeoutOrNull(30_000L) { upSignal.await() }
            if (upEvent == null) {
                readerJob.cancel()
                failChain("PPTP failed to connect within 30s. Check server, credentials, or GRE access.")
                return
            }

            val localIp = upEvent.optString("local_ip")
            val peerIp = upEvent.optString("peer_ip")
            val dns1 = upEvent.optString("dns1")
            val dns2 = upEvent.optString("dns2")
            ChainRuntimeBus.updateStatus { it.copy(pptpLocalIp = localIp, pptpPeerIp = peerIp) }

            // 3. Configure pptp0 interface, routing table 101, and UID policy routing
            val setupRes = RootRouter.setupPptpInterfaceAndTable(
                ifName = RootRouter.PPTP_IFACE,
                localIp = localIp,
                peerIp = peerIp,
                mtu = cfg.pptpMtu,
                pptpServerIp = pptpServerIp,
                phys = phys
            )
            if (setupRes.exitCode != 0) {
                failChain("Failed configuring pptp0 routes: ${setupRes.output}")
                return
            }

            // Route 2nd VPN app UID through pptp0 (table 101) - immune to server/IP switches!
            RootRouter.addUidToPptp(secondVpnUid, isSecondVpn = true)
            ChainRuntimeBus.log(
                LogLevel.OK,
                "Pinned 2nd VPN app (${cfg.secondVpnLabel}, UID $secondVpnUid) -> pptp0 (table 101)"
            )

            // Route PPTP-provided DNS and optional public DNS via pptp0 so 2nd VPN can resolve domains
            if (dns1.isNotBlank()) RootRouter.addDestinationToPptp(dns1, isDns = true)
            if (dns2.isNotBlank()) RootRouter.addDestinationToPptp(dns2, isDns = true)
            if (cfg.routePublicDnsViaPptp) {
                RootRouter.addDestinationToPptp("1.1.1.1", isDns = true)
                RootRouter.addDestinationToPptp("8.8.8.8", isDns = true)
            }

            // 4. Apply Exclude Rules (Apps + Sites/IPs/CIDRs)
            val disallowedPackages = mutableSetOf(packageName, cfg.secondVpnPackage)
            applyExcludeRules(cfg, phys, disallowedPackages)

            // 5. Wait for 2nd VPN local proxy port (e.g. 127.0.0.1:10810)
            val waitSec = cfg.waitForProxySeconds.coerceAtLeast(0)
            if (!isTcpPortOpen(cfg.proxyHost, cfg.proxyPort)) {
                ChainRuntimeBus.setPhase(
                    ConnectionPhase.WAITING_SECOND_VPN,
                    "PPTP is UP! Connect ${cfg.secondVpnLabel} in Proxy mode (port ${cfg.proxyPort})"
                )
                updateNotification("PPTP UP! Connect ${cfg.secondVpnLabel} (port ${cfg.proxyPort})...")
                ChainRuntimeBus.log(
                    LogLevel.WARN,
                    ">>> PPTP is UP! Now connect '${cfg.secondVpnLabel}' in Proxy-Only mode on ${cfg.proxyHost}:${cfg.proxyPort} (waiting ${waitSec}s)..."
                )

                var open = false
                var waited = 0
                while (waited < waitSec && currentCoroutineContext().isActive) {
                    if (isTcpPortOpen(cfg.proxyHost, cfg.proxyPort)) {
                        open = true
                        break
                    }
                    delay(1000L)
                    waited++
                }
                if (!open) {
                    failChain("2nd VPN proxy ${cfg.proxyHost}:${cfg.proxyPort} did not open within ${waitSec}s.")
                    return
                }
            }
            ChainRuntimeBus.log(LogLevel.OK, "2nd VPN local proxy detected on ${cfg.proxyHost}:${cfg.proxyPort}!")

            // 6. Start Android VpnService (tun0) + fd-bridge (SmartProxy + libtun2socks.so)
            ChainRuntimeBus.setPhase(ConnectionPhase.STARTING_TUNNEL, "Starting TUN -> ${cfg.proxyHost}:${cfg.proxyPort}...")
            val builder = Builder()
                .setSession("VPN Chain")
                .setMtu(cfg.tunMtu)
                .addAddress("10.233.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(cfg.dnsServer.ifBlank { "1.1.1.1" })

            for (pkg in disallowedPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                }
            }

            val pfd = builder.establish()
                ?: run {
                    failChain("VpnService.establish() returned null. Grant VPN permission and retry.")
                    return
                }
            tunPfd = pfd

            val sockFile = File(filesDir, "tun_fd.sock")
            if (sockFile.exists()) sockFile.delete()

            val dnsAddr = if (cfg.dnsServer.contains(":")) cfg.dnsServer else "${cfg.dnsServer}:53"
            val bridgeBuilder = ProcessBuilder(
                chainCoreBin.absolutePath,
                "fd-bridge",
                "-sock", sockFile.absolutePath,
                "-tun2socks", tun2socksBin.absolutePath,
                "-upstream", "${cfg.proxyHost}:${cfg.proxyPort}",
                "-type", cfg.proxyProtocol.id,
                "-dns", dnsAddr,
                "-mtu", cfg.tunMtu.toString()
            ).redirectErrorStream(true)

            val bProc = bridgeBuilder.start()
            bridgeProc = bProc

            val readyDeferred = CompletableDeferred<Boolean>()
            val tunUpDeferred = CompletableDeferred<Boolean>()

            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(bProc.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            val text = line!!.trim()
                            if (text.startsWith("{")) {
                                try {
                                    val ev = JSONObject(text)
                                    when (ev.optString("event")) {
                                        "ready" -> if (!readyDeferred.isCompleted) readyDeferred.complete(true)
                                        "tun_up" -> {
                                            ChainRuntimeBus.log(LogLevel.OK, "[TUN] ${ev.optString("message")}")
                                            if (!tunUpDeferred.isCompleted) tunUpDeferred.complete(true)
                                        }
                                        "error" -> {
                                            ChainRuntimeBus.log(LogLevel.ERROR, "[TUN] ${ev.optString("message")}")
                                            if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                                            if (!tunUpDeferred.isCompleted) tunUpDeferred.complete(false)
                                        }
                                        "tun_down" -> ChainRuntimeBus.log(LogLevel.WARN, "[TUN] ${ev.optString("message")}")
                                    }
                                } catch (_: Exception) {
                                    ChainRuntimeBus.log(LogLevel.DIM, "[TUN] $text")
                                }
                            } else if (text.isNotEmpty()) {
                                ChainRuntimeBus.log(LogLevel.DIM, "[TUN] $text")
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(false)
                    if (!tunUpDeferred.isCompleted) tunUpDeferred.complete(false)
                }
            }

            val isReady = withTimeoutOrNull(8_000L) { readyDeferred.await() } ?: false
            if (!isReady) {
                failChain("FD bridge did not become ready.")
                return
            }

            // Transfer VpnService TUN FD over LocalSocket (SCM_RIGHTS)
            sendTunFdOverUnixSocket(sockFile.absolutePath, pfd)

            val isTunUp = withTimeoutOrNull(8_000L) { tunUpDeferred.await() } ?: false
            if (!isTunUp) {
                failChain("tun2socks failed to start.")
                return
            }

            ChainRuntimeBus.setPhase(
                ConnectionPhase.ACTIVE,
                "PPTP ($localIp) -> ${cfg.secondVpnLabel} (:${cfg.proxyPort})"
            )
            updateNotification("Active: PPTP -> ${cfg.secondVpnLabel} (:${cfg.proxyPort})")
            ChainRuntimeBus.log(LogLevel.OK, "SUCCESS: All device traffic is now chained through ${cfg.secondVpnLabel} over PPTP!")

            // Wait until PPTP or bridge exits
            while (currentCoroutineContext().isActive) {
                if (pptpProc?.isAlive == false) {
                    ChainRuntimeBus.log(LogLevel.ERROR, "PPTP process exited unexpectedly.")
                    break
                }
                if (bridgeProc?.isAlive == false) {
                    ChainRuntimeBus.log(LogLevel.ERROR, "tun2socks bridge exited unexpectedly.")
                    break
                }
                delay(2000L)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            failChain("Unexpected error: ${e.message}")
        }
    }

    private fun applyExcludeRules(
        cfg: ChainConfig,
        phys: PhysicalRouteInfo,
        disallowedPackages: MutableSet<String>
    ) {
        val activeRules = cfg.excludeRules.filter { it.enabled && it.target.isNotBlank() }
        if (activeRules.isEmpty()) return

        ChainRuntimeBus.log(LogLevel.INFO, "Applying ${activeRules.size} exclude rule(s)...")
        for (rule in activeRules) {
            when (rule.type) {
                ExcludeType.APPLICATION -> {
                    val pkg = rule.target.trim()
                    val uid = getPackageUidSafe(pkg)
                    if (uid < 0) {
                        ChainRuntimeBus.log(LogLevel.WARN, "[Exclude] App '$pkg' not found.")
                        continue
                    }
                    disallowedPackages.add(pkg)
                    if (rule.route == ExcludeRoute.BASE_VPN) {
                        RootRouter.addUidToPptp(uid, isSecondVpn = false)
                        ChainRuntimeBus.log(LogLevel.OK, "[Exclude] App '${rule.displayName.ifBlank { pkg }}' (UID $uid) -> Base VPN (PPTP only)")
                    } else {
                        ChainRuntimeBus.log(LogLevel.OK, "[Exclude] App '${rule.displayName.ifBlank { pkg }}' (UID $uid) -> Direct (No VPN)")
                    }
                }
                ExcludeType.SITE_OR_CIDR -> {
                    val target = rule.target.trim()
                    if (target.contains("/") && Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$").matches(target)) {
                        if (rule.route == ExcludeRoute.BASE_VPN) {
                            RootRouter.addDestinationToPptp(target)
                        } else {
                            RootRouter.addDestinationDirect(target, phys.table)
                        }
                        ChainRuntimeBus.log(LogLevel.OK, "[Exclude] Subnet $target -> ${rule.route.label}")
                    } else {
                        val ips = RootRouter.resolveIpv4(target)
                        if (ips.isEmpty()) {
                            ChainRuntimeBus.log(LogLevel.WARN, "[Exclude] Could not resolve '$target'")
                        } else {
                            ips.forEach { ip ->
                                if (rule.route == ExcludeRoute.BASE_VPN) {
                                    RootRouter.addDestinationToPptp(ip)
                                } else {
                                    RootRouter.addDestinationDirect(ip, phys.table)
                                }
                            }
                            ChainRuntimeBus.log(
                                LogLevel.OK,
                                "[Exclude] Site '$target' (${ips.joinToString(", ")}) -> ${rule.route.label}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendTunFdOverUnixSocket(sockPath: String, pfd: ParcelFileDescriptor) {
        val sock = LocalSocket()
        sock.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
        sock.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
        sock.outputStream.write(byteArrayOf(0x01))
        sock.outputStream.flush()
        sock.close()
    }

    private fun isTcpPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 800)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getPackageUidSafe(pkg: String): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageUid(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageUid(pkg, 0)
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun failChain(msg: String) {
        ChainRuntimeBus.log(LogLevel.ERROR, msg)
        ChainRuntimeBus.setPhase(ConnectionPhase.ERROR, msg)
        cleanupResources(resetPhase = false)
        stopSelf()
    }

    private fun cleanupResources(resetPhase: Boolean) {
        try { bridgeProc?.destroyForcibly() } catch (_: Exception) {}
        bridgeProc = null
        try { tunPfd?.close() } catch (_: Exception) {}
        tunPfd = null
        try { pptpProc?.destroyForcibly() } catch (_: Exception) {}
        pptpProc = null

        RootRouter.cleanupAllRules()
        ChainRuntimeBus.log(LogLevel.INFO, "Cleaned up PPTP, TUN, and root ip rules.")
        if (resetPhase) {
            ChainRuntimeBus.updateStatus {
                RuntimeStatus(
                    phase = ConnectionPhase.IDLE,
                    statusDetail = "Disconnected & routes cleaned"
                )
            }
        }
    }

    private fun stopChainAndService() {
        chainJob?.cancel()
        serviceScope.launch {
            cleanupResources(resetPhase = true)
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        stopChainAndService()
        super.onRevoke()
    }

    override fun onDestroy() {
        chainJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            cleanupResources(resetPhase = true)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.vpnchain.android.ACTION_START"
        const val ACTION_STOP = "com.vpnchain.android.ACTION_STOP"
        private const val CHANNEL_ID = "vpn_chain_channel"
        private const val NOTIF_ID = 4101
    }
}
