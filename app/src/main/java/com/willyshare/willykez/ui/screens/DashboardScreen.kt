package com.willyshare.willykez.ui.screens

import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.data.TransferEntity
import com.willyshare.willykez.ui.GroupPosition
import com.willyshare.willykez.ui.GroupedListColumn
import com.willyshare.willykez.ui.GroupedListItem
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.SleekBottomNav
import com.willyshare.willykez.ui.formatBytes
import com.willyshare.willykez.ui.groupPositionFor
import com.willyshare.willykez.ui.theme.BlueThumb
import com.willyshare.willykez.ui.theme.CyanBright
import com.willyshare.willykez.ui.theme.SleekBg
import com.willyshare.willykez.ui.theme.GreenThumb
import com.willyshare.willykez.ui.theme.PinkThumb
import com.willyshare.willykez.ui.theme.SleekOnPrimaryContainer
import com.willyshare.willykez.ui.theme.SleekOnSecondaryContainer
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.SleekPrimaryContainer
import com.willyshare.willykez.ui.theme.SleekSecondary
import com.willyshare.willykez.ui.theme.SleekSecondaryContainer
import com.willyshare.willykez.ui.theme.SleekSurfaceContainer

/**
 * Home. A circular ring-based stats cluster up top (not flat text chips), then Send/Receive
 * as full-width stacked "command rows" - a hub-menu feel rather than side-by-side bento
 * tiles - a secondary "choose files first" row, and Recent Activity below.
 */
@Composable
fun DashboardScreen(
    viewModel: PulseViewModel,
    onNavigate: (String) -> Unit
) {
    val transfers by viewModel.transfers.collectAsState()
    val deviceName by viewModel.thisDeviceName.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    val targetName by viewModel.targetName.collectAsState()
    val totalBytes = transfers.sumOf { it.sizeBytes }
    val sentCount = transfers.count { it.isSend }
    val receivedCount = transfers.count { !it.isSend }

    Scaffold(
        bottomBar = {
            SleekBottomNav(currentRoute = "dashboard", onNavigate = onNavigate)
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            InPageHeader(
                title = "Sharing Plus",
                subtitle = deviceName,
                rightIcon = Icons.Default.Settings,
                onRightClick = { onNavigate("settings") }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (linkState != com.willyshare.willykez.ui.LinkState.IDLE) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        ConnectionStatusBanner(
                            linkState = linkState,
                            peerName = targetName,
                            onDisconnect = { viewModel.resetConnection() }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    StatsRingCluster(deviceName = deviceName, sentCount = sentCount, receivedCount = receivedCount, totalBytes = totalBytes)
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommandRow(
                            icon = com.willyshare.willykez.ui.PulseIcons.Send,
                            title = "Send",
                            subtitle = "Pick files, find a device",
                            containerColor = SleekPrimaryContainer,
                            onColor = SleekOnPrimaryContainer,
                            onClick = { onNavigate("send") }
                        )
                        CommandRow(
                            icon = com.willyshare.willykez.ui.PulseIcons.Receive,
                            title = "Receive",
                            subtitle = "Wait for an incoming file",
                            containerColor = SleekSecondaryContainer,
                            onColor = SleekOnSecondaryContainer,
                            onClick = { onNavigate("receive") }
                        )
                    }
                }

                item {
                    val accentColor = SleekPrimary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekSurfaceContainer)
                            .drawBehind {
                                drawLine(
                                    color = accentColor.copy(alpha = 0.35f),
                                    start = androidx.compose.ui.geometry.Offset(size.width * 0.04f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(size.width * 0.3f, 0f),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                            .clickable { onNavigate("select") }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            com.willyshare.willykez.ui.PulseIcons.FolderOpenEmpty,
                            contentDescription = null,
                            tint = SleekOnSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Choose files first, connect after", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SleekOnSurface, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = SleekOnSurfaceVariant.copy(alpha = 0.5f))
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECENT ACTIVITY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekOnSurfaceVariant,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        Text(
                            text = "View all",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekPrimary,
                            modifier = Modifier.clickable { onNavigate("history") }
                        )
                    }
                }

                if (transfers.isEmpty()) {
                    item {
                        GroupedListColumn {
                            GroupedListItem(position = GroupPosition.ONLY) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        com.willyshare.willykez.ui.PulseIcons.EmptyInbox,
                                        contentDescription = null,
                                        tint = SleekOnSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("No transfers yet", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                                    Text("Tap Send or Receive to get started", fontSize = 12.sp, color = SleekOnSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    item {
                        GroupedListColumn {
                            val shown = transfers.take(6)
                            shown.forEachIndexed { index, transfer ->
                                GroupedListItem(position = groupPositionFor(index, shown.size)) {
                                    TransferItemRow(
                                        transfer = transfer,
                                        onDelete = { viewModel.deleteTransfer(transfer) }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

/**
 * A ring-based stats cluster instead of flat text chips - a small circular arc meter for
 * each of Sent/Received, split proportionally, plus the device name and total. Reads as a
 * dashboard/analytics widget rather than a plain identity label row.
 */
@Composable
private fun StatsRingCluster(
    deviceName: String,
    sentCount: Int,
    receivedCount: Int,
    totalBytes: Long
) {
    val total = (sentCount + receivedCount).coerceAtLeast(1)
    val sentFraction = sentCount.toFloat() / total.toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SleekSurfaceContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val primary = SleekPrimary
        val secondary = SleekSecondary
        val track = SleekOutline
        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                drawArc(
                    color = track.copy(alpha = 0.18f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                if (sentCount + receivedCount > 0) {
                    drawArc(
                        color = primary,
                        startAngle = -90f, sweepAngle = 360f * sentFraction, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                    drawArc(
                        color = secondary,
                        startAngle = -90f + 360f * sentFraction, sweepAngle = 360f * (1f - sentFraction), useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }
            Text(formatBytes(totalBytes), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "THIS DEVICE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SleekOnSurfaceVariant.copy(alpha = 0.6f),
                letterSpacing = 1.2.sp
            )
            Text(
                text = deviceName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = SleekOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                RingLegendDot(color = SleekPrimary, label = "$sentCount sent")
                Spacer(modifier = Modifier.width(12.dp))
                RingLegendDot(color = SleekSecondary, label = "$receivedCount received")
            }
        }
    }
}

@Composable
private fun RingLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = SleekOnSurfaceVariant)
    }
}

/**
 * Full-width command rows replacing the earlier side-by-side bento tiles - a "hub menu" feel
 * (icon badge, stacked title/subtitle, trailing chevron) rather than a grid of square cards.
 * Stacked vertically instead of side-by-side is the actual structural change here.
 */
@Composable
private fun CommandRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    onColor: Color,
    onClick: () -> Unit
) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "row_pulse")
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.10f, targetValue = 0.22f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(containerColor)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(onColor.copy(alpha = glowAlpha))
            )
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(onColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = onColor, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Black, color = onColor)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = onColor.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = onColor.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DashboardSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = SleekOnSurfaceVariant,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        GroupedListColumn {
            content()
        }
    }
}

@Composable
fun TransferItemRow(
    transfer: TransferEntity,
    onDelete: () -> Unit
) {
    val categoryIcon = com.willyshare.willykez.ui.PulseIcons.forCategory(transfer.category)
    val iconBg = when (transfer.category) {
        "VIDEO" -> CyanBright.copy(alpha = 0.2f)
        "PHOTO" -> PinkThumb
        "AUDIO" -> GreenThumb
        else -> BlueThumb
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val canOpen = !transfer.isSend && !transfer.savedPath.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (canOpen) {
                    Modifier.clickable {
                        com.willyshare.willykez.util.FileOpener.open(context, transfer.savedPath!!, transfer.fileName)
                    }
                } else Modifier
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(categoryIcon, contentDescription = null, tint = SleekOnSurface, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = transfer.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (transfer.isSend) "\u2191" else "\u2193",
                        fontSize = 11.sp,
                        color = SleekOnSurfaceVariant,
                        modifier = Modifier.alpha(0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${transfer.deviceName} \u00B7 ${formatBytes(transfer.sizeBytes)}",
                        fontSize = 12.sp,
                        color = SleekOnSurfaceVariant,
                        modifier = Modifier.alpha(0.85f)
                    )
                }
            }
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SleekOnSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The real "panic button" for a stuck or active connection - reachable from Home no matter
 * which screen originally created the connection (Send, Receive, QR, whatever). Previously
 * there was no way to back out of a half-formed pairing short of restarting the app.
 */
@Composable
private fun ConnectionStatusBanner(
    linkState: com.willyshare.willykez.ui.LinkState,
    peerName: String?,
    onDisconnect: () -> Unit
) {
    val label = when (linkState) {
        com.willyshare.willykez.ui.LinkState.TRANSFERRING -> "Transferring with ${peerName ?: "a nearby device"}"
        com.willyshare.willykez.ui.LinkState.CONNECTED -> "Connected to ${peerName ?: "a nearby device"}"
        com.willyshare.willykez.ui.LinkState.IDLE -> ""
    }
    val dotColor = if (linkState == com.willyshare.willykez.ui.LinkState.TRANSFERRING) SleekPrimary else Color(0xFF2E7D32)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(SleekSurfaceContainer)
            .border(1.dp, SleekOutline.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SleekOnSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Disconnect",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD32F2F),
            modifier = Modifier.clickable { onDisconnect() }.padding(start = 8.dp)
        )
    }
}
