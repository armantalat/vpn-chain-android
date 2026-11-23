package com.vpnchain.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnchain.android.model.LogLevel
import com.vpnchain.android.ui.ChainViewModel
import com.vpnchain.android.ui.theme.*

@Composable
fun LogsScreen(vm: ChainViewModel) {
    val logs by vm.logs.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.dumpDiagnostics() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Inspect ip rule", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = {
                    val allText = logs.joinToString("\n") { "[${it.timestamp}] ${it.message}" }
                    clipboard.setText(AnnotatedString(allText))
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = SlateText, modifier = Modifier.size(18.dp))
            }

            OutlinedButton(
                onClick = { vm.clearLogs() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear", tint = BrandRed, modifier = Modifier.size(18.dp))
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateBg),
            border = BorderStroke(1.dp, SlateBorder)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(SlateBg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { entry ->
                    val col = when (entry.level) {
                        LogLevel.INFO -> BrandBlueLight
                        LogLevel.OK -> BrandGreen
                        LogLevel.WARN -> BrandAmber
                        LogLevel.ERROR -> BrandRed
                        LogLevel.DIM -> SlateMuted
                    }
                    Text(
                        text = "[${entry.timestamp}] ${entry.message}",
                        color = col,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
