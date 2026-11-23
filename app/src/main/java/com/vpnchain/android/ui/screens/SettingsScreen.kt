package com.vpnchain.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnchain.android.model.ConnectionPhase
import com.vpnchain.android.ui.ChainViewModel
import com.vpnchain.android.ui.theme.*

@Composable
fun SettingsScreen(vm: ChainViewModel) {
    val cfg by vm.config.collectAsState()
    val status by vm.status.collectAsState()
    val isRunning = status.phase != ConnectionPhase.IDLE && status.phase != ConnectionPhase.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tunnel & MTU card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = BrandBlueLight)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tunnel, MTU & DNS", color = BrandBlueLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cfg.pptpMtu.toString(),
                        onValueChange = { v ->
                            v.filter { it.isDigit() }.toIntOrNull()?.let { n ->
                                if (n in 1200..1460) vm.updateConfig { it.copy(pptpMtu = n) }
                            }
                        },
                        enabled = !isRunning,
                        label = { Text("PPTP MTU") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = cfg.tunMtu.toString(),
                        onValueChange = { v ->
                            v.filter { it.isDigit() }.toIntOrNull()?.let { n ->
                                if (n in 1200..1500) vm.updateConfig { it.copy(tunMtu = n) }
                            }
                        },
                        enabled = !isRunning,
                        label = { Text("TUN MTU") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cfg.dnsServer,
                        onValueChange = { v -> vm.updateConfig { it.copy(dnsServer = v.trim()) } },
                        enabled = !isRunning,
                        label = { Text("Tunnel DNS Server") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = cfg.waitForProxySeconds.toString(),
                        onValueChange = { v ->
                            v.filter { it.isDigit() }.toIntOrNull()?.let { n ->
                                if (n in 0..300) vm.updateConfig { it.copy(waitForProxySeconds = n) }
                            }
                        },
                        enabled = !isRunning,
                        label = { Text("Proxy Wait (s)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stateless MPPE-128", color = SlateText, fontSize = 14.sp)
                        Text("Recommended for resilience over mobile/Wi-Fi packet loss", color = SlateMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = cfg.statelessMppe,
                        enabled = !isRunning,
                        onCheckedChange = { v -> vm.updateConfig { it.copy(statelessMppe = v) } }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Route 1.1.1.1 & 8.8.8.8 via PPTP", color = SlateText, fontSize = 14.sp)
                        Text("Helps the 2nd VPN resolve domains when local Wi-Fi has no internet DNS", color = SlateMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = cfg.routePublicDnsViaPptp,
                        enabled = !isRunning,
                        onCheckedChange = { v -> vm.updateConfig { it.copy(routePublicDnsViaPptp = v) } }
                    )
                }
            }
        }

        // Maintenance Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Maintenance & Recovery", color = BrandAmber, fontSize = 15.sp, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = { vm.loadInstalledApps() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = BrandBlueLight)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Installed Apps List", color = SlateText)
                }

                Button(
                    onClick = { vm.forceCleanupRules() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Emergency Cleanup All Root Routes")
                }
            }
        }
    }
}
