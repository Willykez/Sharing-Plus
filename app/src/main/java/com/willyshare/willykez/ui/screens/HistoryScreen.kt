package com.willyshare.willykez.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.data.TransferEntity
import com.willyshare.willykez.ui.AuroraBackground
import com.willyshare.willykez.ui.FilePreviewDialog
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.PreviewableFile
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.SleekBottomNav
import com.willyshare.willykez.ui.theme.SleekCard
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary

private enum class HistoryTab(val label: String) { ALL("All"), SENT("Sent"), RECEIVED("Received") }

@Composable
fun HistoryScreen(
    viewModel: PulseViewModel,
    onNavigate: (String) -> Unit
) {
    val transfers by viewModel.transfers.collectAsState()
    var tab by remember { mutableStateOf(HistoryTab.ALL) }
    var previewTransfer by remember { mutableStateOf<TransferEntity?>(null) }

    val visibleTransfers = when (tab) {
        HistoryTab.ALL -> transfers
        HistoryTab.SENT -> transfers.filter { it.isSend }
        HistoryTab.RECEIVED -> transfers.filter { !it.isSend }
    }

    val now = System.currentTimeMillis()
    val dayMillis = 86400000L

    val todayTransfers = visibleTransfers.filter { (now - it.timestamp) < dayMillis }
    val olderTransfers = visibleTransfers.filter { (now - it.timestamp) >= dayMillis }

    Scaffold(
        bottomBar = {
            SleekBottomNav(currentRoute = "history", onNavigate = onNavigate)
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { innerPadding ->
        AuroraBackground(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
            InPageHeader(
                title = "Transfer History",
                showBack = true,
                onBack = { onNavigate("dashboard") },
                rightIcon = if (transfers.isNotEmpty()) Icons.Default.DeleteSweep else null,
                onRightClick = { viewModel.clearAllHistory() }
            )

            // Sent vs Received is the single most common question someone has looking at a
            // mixed history list ("wait, did I send that or get it?") - a filter right under
            // the header answers it before they have to read every row's arrow icon.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sentCount = transfers.count { it.isSend }
                val receivedCount = transfers.count { !it.isSend }
                HistoryTab.entries.forEach { option ->
                    val count = when (option) {
                        HistoryTab.ALL -> transfers.size
                        HistoryTab.SENT -> sentCount
                        HistoryTab.RECEIVED -> receivedCount
                    }
                    val isSelected = option == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isSelected) SleekPrimary else SleekCard)
                            .border(1.dp, if (isSelected) SleekPrimary else SleekOutline.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .clickable { tab = option }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${option.label} ($count)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else SleekOnSurfaceVariant
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            item {
                Text(
                    text = "${visibleTransfers.size} record${if (visibleTransfers.size != 1) "s" else ""} stored on this device",
                    fontSize = 13.sp,
                    color = SleekOnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (visibleTransfers.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(com.willyshare.willykez.ui.PulseIcons.EmptyInbox, contentDescription = null, tint = SleekOnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (tab) {
                                HistoryTab.ALL -> "No Transfer History Yet"
                                HistoryTab.SENT -> "Nothing Sent Yet"
                                HistoryTab.RECEIVED -> "Nothing Received Yet"
                            },
                            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface
                        )
                        Text("Files sent or received will appear here", fontSize = 13.sp, color = SleekOnSurfaceVariant)
                    }
                }
            }

            if (todayTransfers.isNotEmpty()) {
                item {
                    Text(
                        text = "TODAY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekOnSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(todayTransfers, key = { it.id }) { transfer ->
                    TransferItemRow(
                        transfer = transfer,
                        onDelete = { viewModel.deleteTransfer(transfer) },
                        onPreview = { previewTransfer = transfer }
                    )
                }
            }

            if (olderTransfers.isNotEmpty()) {
                item {
                    Text(
                        text = "EARLIER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekOnSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(olderTransfers, key = { it.id }) { transfer ->
                    TransferItemRow(
                        transfer = transfer,
                        onDelete = { viewModel.deleteTransfer(transfer) },
                        onPreview = { previewTransfer = transfer }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
        }

        previewTransfer?.let { transfer ->
            FilePreviewDialog(
                file = PreviewableFile.from(transfer),
                onDismiss = { previewTransfer = null }
            )
        }
        }
    }
}
