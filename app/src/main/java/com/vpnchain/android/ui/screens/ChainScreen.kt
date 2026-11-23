package com.vpnchain.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnchain.android.model.*
import com.vpnchain.android.ui.ChainViewModel
import com.vpnchain.android.ui.theme.*

@Composable
fun ChainScreen(
    vm: ChainViewModel,
    onRequestConnect: () -> Unit
) {
    val cfg by vm.config.collectAsState()
    val status by vm.status.collectAsState()
    val isRooted by vm.isRooted.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val loadingApps by vm.loadingApps.collectAsState()

    var showPassword by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val isRunning = status.phase != ConnectionPhase.IDLE && status.phase != ConnectionPhase.ERROR

    val pillColor = when (status.phase) {
        ConnectionPhase.ACTIVE -> BrandGreen
        ConnectionPhase.CONNECTING_PPTP, ConnectionPhase.WAITING_SECOND_VPN, ConnectionPhase.STARTING_TUNNEL -> BrandAmber
        ConnectionPhase.ERROR -> BrandRed
        ConnectionPhase.IDLE -> SlateMuted
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------------- HERO CONNECTION CARD ----------------
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, if (status.phase == ConnectionPhase.ACTIVE) BrandGreen.copy(alpha = 0.6f) else SlateBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Status Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(pillColor.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(pillColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = status.phase.label,
                            color = SlateText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Root Status Chip
                    val rootCol = when (isRooted) {
                        true -> BrandGreen
                        false -> BrandRed
                        null -> BrandAmber
                    }
                    val rootTxt = when (isRooted) {
                        true -> "Root OK"
                        false -> "No Root"
                        null -> "Checking..."
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(rootCol.copy(alpha = 0.14f))
                            .clickable { vm.checkRoot() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = rootCol,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(rootTxt, color = rootCol, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = status.statusDetail,
                    color = SlateText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                if (status.pptpLocalIp.isNotBlank() || status.publicIp.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (status.pptpLocalIp.isNotBlank()) {
                            Text("PPTP IP: ${status.pptpLocalIp}", color = BrandBlueLight, fontSize = 12.sp)
                        }
                        if (status.publicIp.isNotBlank()) {
                            Text("Public IP: ${status.publicIp}", color = BrandGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Main Connect / Disconnect Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (isRunning) vm.stopChainService() else onRequestConnect()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) BrandRed else BrandGreen
                        )
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "Disconnect Chain" else "Start VPN Chain",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { vm.testPublicIp() },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SlateBorder)
                    ) {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = "Test IP", tint = BrandBlueLight)
                    }
                }

                // Helpful quick-launch button when waiting for user to connect 2nd VPN
                if (status.phase == ConnectionPhase.WAITING_SECOND_VPN && cfg.secondVpnPackage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { vm.launchSecondVpnApp() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open ${cfg.secondVpnLabel.ifBlank { cfg.secondVpnPackage }} (Connect Proxy :${cfg.proxyPort})")
                    }
                }
            }
        }

        // ---------------- CARD 1: BASE VPN (PPTP) ----------------
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VpnKey, contentDescription = null, tint = BrandBlueLight, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "1. Base VPN (Userspace PPTP)",
                        color = BrandBlueLight,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = cfg.pptpServer,
                    onValueChange = { v -> vm.updateConfig { it.copy(pptpServer = v.trim()) } },
                    enabled = !isRunning,
                    label = { Text("PPTP Server IP or Domain") },
                    placeholder = { Text("e.g. 172.16.x.x or vpn.company.local") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cfg.pptpUsername,
                        onValueChange = { v -> vm.updateConfig { it.copy(pptpUsername = v) } },
                        enabled = !isRunning,
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = cfg.pptpPassword,
                        onValueChange = { v -> vm.updateConfig { it.copy(pptpPassword = v) } },
                        enabled = !isRunning,
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = SlateMuted
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Auth Mode Chips
                Text("Authentication", color = SlateMuted, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuthMode.entries.forEach { mode ->
                        FilterChip(
                            selected = cfg.authMode == mode,
                            onClick = { if (!isRunning) vm.updateConfig { it.copy(authMode = mode) } },
                            label = {
                                Text(
                                    text = if (mode == AuthMode.AUTO) "Auto" else mode.label,
                                    fontSize = 11.sp
                                )
                            },
                            enabled = !isRunning
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MPPE-128 Encryption", color = SlateText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Standard encryption for MS-CHAPv2 PPTP", color = SlateMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = cfg.requireMppe,
                        enabled = !isRunning,
                        onCheckedChange = { v -> vm.updateConfig { it.copy(requireMppe = v) } }
                    )
                }
            }
        }

        // ---------------- CARD 2: 2ND VPN PROXY ----------------
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "2. Second VPN App (Proxy Mode)",
                        color = BrandGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Pick 2nd VPN App row
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isRunning) { showAppPicker = true },
                    color = SlateCardAlt,
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(listOf(BrandBlue, BrandGreen))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (cfg.secondVpnLabel.isNotBlank()) cfg.secondVpnLabel else "Tap to select 2nd VPN App",
                                color = SlateText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (cfg.secondVpnPackage.isNotBlank()) cfg.secondVpnPackage else "Only this app will route through PPTP (anti-loop by UID)",
                                color = SlateMuted,
                                fontSize = 11.sp
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = SlateMuted)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cfg.proxyHost,
                        onValueChange = { v -> vm.updateConfig { it.copy(proxyHost = v.trim()) } },
                        enabled = !isRunning,
                        label = { Text("Proxy Host") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = cfg.proxyPort.toString(),
                        onValueChange = { v ->
                            val parsed = v.filter { it.isDigit() }.toIntOrNull()
                            if (parsed != null && parsed in 1..65535) {
                                vm.updateConfig { it.copy(proxyPort = parsed) }
                            } else if (v.isEmpty()) {
                                vm.updateConfig { it.copy(proxyPort = 10810) }
                            }
                        },
                        enabled = !isRunning,
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Proxy Protocol Chips
                Text("Local Proxy Protocol (Smart DNS-over-TCP built-in)", color = SlateMuted, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProxyProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = cfg.proxyProtocol == proto,
                            onClick = { if (!isRunning) vm.updateConfig { it.copy(proxyProtocol = proto) } },
                            label = { Text(proto.label, fontSize = 11.sp) },
                            enabled = !isRunning
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "Select 2nd VPN App",
            apps = apps,
            isLoading = loadingApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                vm.updateConfig {
                    it.copy(secondVpnPackage = app.packageName, secondVpnLabel = app.label)
                }
                showAppPicker = false
            }
        )
    }
}
