package com.vpnchain.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vpnchain.android.ui.ChainViewModel
import com.vpnchain.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: ChainViewModel,
    onRequestConnect: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val cfg by vm.config.collectAsState()
    val activeExcludes = cfg.excludeRules.count { it.enabled }

    Scaffold(
        containerColor = SlateBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "VPN Chain",
                            color = SlateText,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "PPTP -> 2nd VPN Proxy Tunnel",
                            color = SlateMuted,
                            fontSize = 11.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SlateSurface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Link, contentDescription = "Chain") },
                    label = { Text("Chain") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (activeExcludes > 0) {
                                    Badge(containerColor = BrandPurple) {
                                        Text(activeExcludes.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.AltRoute, contentDescription = "Exclude")
                        }
                    },
                    label = { Text("Exclude") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChainScreen(vm = vm, onRequestConnect = onRequestConnect)
                1 -> ExcludeScreen(vm = vm)
                2 -> SettingsScreen(vm = vm)
                3 -> LogsScreen(vm = vm)
            }
        }
    }
}
