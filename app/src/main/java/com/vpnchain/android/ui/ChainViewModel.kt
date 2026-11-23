package com.vpnchain.android.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpnchain.android.core.RootRouter
import com.vpnchain.android.data.ConfigStore
import com.vpnchain.android.model.*
import com.vpnchain.android.service.ChainRuntimeBus
import com.vpnchain.android.service.ChainVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ChainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConfigStore(application)

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<ChainConfig> = _config.asStateFlow()

    val status: StateFlow<RuntimeStatus> = ChainRuntimeBus.status
    val logs: StateFlow<List<LogEntry>> = ChainRuntimeBus.logs

    private val _isRooted = MutableStateFlow<Boolean?>(null)
    val isRooted: StateFlow<Boolean?> = _isRooted.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _loadingApps = MutableStateFlow(false)
    val loadingApps: StateFlow<Boolean> = _loadingApps.asStateFlow()

    init {
        checkRoot()
        loadInstalledApps()
    }

    fun updateConfig(transform: (ChainConfig) -> ChainConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        store.save(updated)
    }

    fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = RootRouter.checkRootAccess()
            _isRooted.value = ok
            ChainRuntimeBus.log(
                if (ok) LogLevel.OK else LogLevel.WARN,
                if (ok) "Root access verified (uid=0)." else "Root access not granted yet."
            )
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _loadingApps.value = true
            val pm = getApplication<Application>().packageManager
            val myPkg = getApplication<Application>().packageName
            val apps = try {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
                list.filter { it.packageName != myPkg }
                    .map { ai ->
                        InstalledAppInfo(
                            packageName = ai.packageName,
                            label = pm.getApplicationLabel(ai).toString(),
                            uid = ai.uid,
                            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            icon = try { pm.getApplicationIcon(ai) } catch (_: Exception) { null }
                        )
                    }
                    .sortedWith(compareBy<InstalledAppInfo> { it.isSystem }.thenBy { it.label.lowercase() })
            } catch (_: Exception) {
                emptyList()
            }
            _installedApps.value = apps
            _loadingApps.value = false
        }
    }

    fun startChainService() {
        val app = getApplication<Application>()
        val intent = Intent(app, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun stopChainService() {
        val app = getApplication<Application>()
        val intent = Intent(app, ChainVpnService::class.java).setAction(ChainVpnService.ACTION_STOP)
        app.startService(intent)
    }

    fun launchSecondVpnApp() {
        val pkg = _config.value.secondVpnPackage
        if (pkg.isBlank()) return
        val app = getApplication<Application>()
        val launchIntent = app.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(launchIntent)
        }
    }

    fun testPublicIp() {
        viewModelScope.launch(Dispatchers.IO) {
            ChainRuntimeBus.log(LogLevel.INFO, "Fetching public IP...")
            val endpoints = listOf("https://api.ipify.org", "http://api.ipify.org", "https://icanhazip.com")
            var found: String? = null
            for (url in endpoints) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        requestMethod = "GET"
                    }
                    val ip = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText().trim() }
                    conn.disconnect()
                    if (ip.isNotBlank()) {
                        found = ip
                        break
                    }
                } catch (_: Exception) {
                }
            }
            if (found != null) {
                ChainRuntimeBus.updateStatus { it.copy(publicIp = found) }
                ChainRuntimeBus.log(LogLevel.OK, "Public IP: $found")
            } else {
                ChainRuntimeBus.log(LogLevel.WARN, "Could not fetch Public IP (timeout).")
            }
        }
    }

    fun forceCleanupRules() {
        viewModelScope.launch(Dispatchers.IO) {
            ChainRuntimeBus.log(LogLevel.WARN, "Running emergency root cleanup...")
            RootRouter.cleanupAllRules()
            ChainRuntimeBus.updateStatus { RuntimeStatus(phase = ConnectionPhase.IDLE, statusDetail = "Emergency cleanup done") }
            ChainRuntimeBus.log(LogLevel.OK, "All ip rules (4800..5110) and table 101 flushed.")
        }
    }

    fun dumpDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val dump = RootRouter.dumpDiagnostics()
            ChainRuntimeBus.log(LogLevel.DIM, dump)
        }
    }

    fun clearLogs() = ChainRuntimeBus.clearLogs()

    fun addExcludeRule(rule: ExcludeRule) {
        updateConfig { it.copy(excludeRules = it.excludeRules + rule) }
        ChainRuntimeBus.log(LogLevel.OK, "Added exclude rule: ${rule.target} -> ${rule.route.label}")
    }

    fun toggleExcludeRule(id: String) {
        updateConfig { cfg ->
            cfg.copy(excludeRules = cfg.excludeRules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        }
    }

    fun deleteExcludeRule(id: String) {
        updateConfig { cfg ->
            cfg.copy(excludeRules = cfg.excludeRules.filterNot { it.id == id })
        }
    }

    fun addIntranetPreset() {
        val presets = listOf(
            "10.0.0.0/8" to "Private 10/8 (Intranet)",
            "172.16.0.0/12" to "Private 172.16/12 (Intranet)",
            "192.168.0.0/16" to "Private 192.168/16 (LAN)"
        )
        updateConfig { cfg ->
            val current = cfg.excludeRules.toMutableList()
            for ((cidr, label) in presets) {
                val idx = current.indexOfFirst { it.type == ExcludeType.SITE_OR_CIDR && it.target == cidr }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(enabled = true, route = ExcludeRoute.DIRECT)
                } else {
                    current.add(
                        ExcludeRule(
                            enabled = true,
                            type = ExcludeType.SITE_OR_CIDR,
                            target = cidr,
                            displayName = label,
                            route = ExcludeRoute.DIRECT
                        )
                    )
                }
            }
            cfg.copy(excludeRules = current)
        }
        ChainRuntimeBus.log(LogLevel.OK, "Enabled Local Intranet Direct preset (10/8, 172.16/12, 192.168/16).")
    }
}
