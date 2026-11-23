package com.vpnchain.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnchain.android.model.*
import com.vpnchain.android.ui.ChainViewModel
import com.vpnchain.android.ui.theme.*

@Composable
fun ExcludeScreen(vm: ChainViewModel) {
    val cfg by vm.config.collectAsState()
    val apps by vm.installedApps.collectAsState()
    val loadingApps by vm.loadingApps.collectAsState()

    var selectedType by remember { mutableStateOf(ExcludeType.APPLICATION) }
    var selectedRoute by remember { mutableStateOf(ExcludeRoute.DIRECT) }
    var siteInput by remember { mutableStateOf("") }
    var showAppPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Add Rule Card
        item {
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
                        Icon(Icons.Rounded.AltRoute, contentDescription = null, tint = BrandPurple, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add Exclude / Split-Tunnel Rule",
                            color = SlateText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Choose apps or websites/subnets to bypass the 2nd VPN (go via PPTP only) or bypass BOTH VPNs (Direct).",
                        color = SlateMuted,
                        fontSize = 12.sp
                    )

                    // Route selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExcludeRoute.entries.forEach { route ->
                            FilterChip(
                                selected = selectedRoute == route,
                                onClick = { selectedRoute = route },
                                label = { Text(route.label, fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Type selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == ExcludeType.APPLICATION,
                            onClick = { selectedType = ExcludeType.APPLICATION },
                            leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("App", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedType == ExcludeType.SITE_OR_CIDR,
                            onClick = { selectedType = ExcludeType.SITE_OR_CIDR },
                            leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Site / IP / CIDR", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (selectedType == ExcludeType.APPLICATION) {
                        Button(
                            onClick = { showAppPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Installed App -> ${selectedRoute.label}")
                        }
                    } else {
                        OutlinedTextField(
                            value = siteInput,
                            onValueChange = { siteInput = it },
                            label = { Text("Domain, IP, or CIDR") },
                            placeholder = { Text("e.g. bank.local, 10.0.0.0/8, 1.2.3.4") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Button(
                            onClick = {
                                val clean = siteInput.trim()
                                if (clean.isNotEmpty()) {
                                    vm.addExcludeRule(
                                        ExcludeRule(
                                            enabled = true,
                                            type = ExcludeType.SITE_OR_CIDR,
                                            target = clean,
                                            displayName = clean,
                                            route = selectedRoute
                                        )
                                    )
                                    siteInput = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Site / Subnet Rule")
                        }
                    }

                    OutlinedButton(
                        onClick = { vm.addIntranetPreset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateBorder)
                    ) {
                        Text("+ Enable Corporate Intranet Preset (Direct)", color = BrandBlueLight, fontSize = 12.sp)
                    }
                }
            }
        }

        // Header for configured rules
        item {
            Text(
                text = "Configured Rules (${cfg.excludeRules.count { it.enabled }} active)",
                color = SlateMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (cfg.excludeRules.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateCardAlt)
                ) {
                    Text(
                        text = "No exclude rules added yet. All apps (except the 2nd VPN) will route through the full chain.",
                        color = SlateMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(cfg.excludeRules, key = { it.id }) { rule ->
                ExcludeRuleCard(
                    rule = rule,
                    onToggle = { vm.toggleExcludeRule(rule.id) },
                    onDelete = { vm.deleteExcludeRule(rule.id) }
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "Exclude App (${selectedRoute.label})",
            apps = apps,
            isLoading = loadingApps,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                vm.addExcludeRule(
                    ExcludeRule(
                        enabled = true,
                        type = ExcludeType.APPLICATION,
                        target = app.packageName,
                        displayName = app.label,
                        route = selectedRoute
                    )
                )
                showAppPicker = false
            }
        )
    }
}

@Composable
private fun ExcludeRuleCard(
    rule: ExcludeRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val badgeColor = if (rule.route == ExcludeRoute.BASE_VPN) BrandBlue else BrandAmber

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (rule.type == ExcludeType.APPLICATION) Icons.Rounded.Apps else Icons.Rounded.Language,
                contentDescription = null,
                tint = if (rule.enabled) BrandPurple else SlateMuted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.displayName.ifBlank { rule.target },
                    color = if (rule.enabled) SlateText else SlateMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (rule.displayName.isNotBlank() && rule.displayName != rule.target) {
                    Text(rule.target, color = SlateMuted, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(rule.route.label, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = BrandRed)
            }
        }
    }
}
