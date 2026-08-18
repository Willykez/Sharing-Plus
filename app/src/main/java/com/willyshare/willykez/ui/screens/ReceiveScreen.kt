package com.willyshare.willykez.ui.screens

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.ui.AuroraBackground
import com.willyshare.willykez.ui.FileProgressRow
import com.willyshare.willykez.ui.GlassCard
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.RadarPulseRing
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.SleekFloatingPillButton
import com.willyshare.willykez.ui.formatBytes
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.SleekPrimaryContainer
import com.willyshare.willykez.ui.theme.SleekSecondary
import com.willyshare.willykez.ui.theme.SleekSecondaryContainer
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

@OptIn(ExperimentalPermissionsApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(viewModel: PulseViewModel, onNavigate: (String) -> Unit) {
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionsState = rememberMultiplePermissionsState(requiredPermissions)
    var showScanSheet by remember { mutableStateOf(false) }

    val isListening by viewModel.isListening.collectAsState()
    val senderConnected by viewModel.senderConnected.collectAsState()
    val hostHasPeer by viewModel.hostHasPeer.collectAsState()
    val connectedPeerIp by viewModel.connectedPeerIp.collectAsState()
    val progress by viewModel.receiveProgress.collectAsState()
    val deviceName by viewModel.thisDeviceName.collectAsState()

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) permissionsState.launchMultiplePermissionRequest()
        // Receiving itself is started once, app-wide, in the ViewModel's init{} - it no
        // longer needs (or should) start/stop with this screen's lifecycle.
    }
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.startPeerDiscovery()
            // Advertises this device over BLE so a sender's scan can bootstrap straight to
            // our Wi-Fi Direct address - see BleNearbyManager. Safe/idempotent if already
            // started from the ViewModel's own init{}; this just covers the case where
            // Bluetooth permission was only just granted on this screen.
            viewModel.refreshBle()
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPeerDiscovery() }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        AuroraBackground(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    InPageHeader(
                        title = "Receive",
                        subtitle = when {
                            !permissionsState.allPermissionsGranted -> "Permission needed"
                            isListening -> "Visible as \u201C$deviceName\u201D"
                            else -> "Starting listener\u2026"
                        },
                        showBack = true,
                        onBack = { onNavigate("dashboard") }
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!permissionsState.allPermissionsGranted) {
                            Spacer(modifier = Modifier.height(60.dp))
                            Icon(com.willyshare.willykez.ui.PulseIcons.TargetPin, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Nearby device permission needed", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Sharing Plus needs this permission so nearby senders can find and connect to this device.",
                                fontSize = 13.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { permissionsState.launchMultiplePermissionRequest() },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                            ) {
                                Text(
                                    if (permissionsState.permissions.any { it.status.shouldShowRationale }) "Grant permission" else "Allow",
                                    color = Color.White, fontWeight = FontWeight.Bold
                                )
                            }
                            return@Column
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        val outlineColor = SleekOutline
                        val primaryColor = SleekPrimary
                        val secondaryColor = SleekSecondary
                        val fraction = if (progress.overallTotal > 0) (progress.overallBytes.toFloat() / progress.overallTotal.toFloat()).coerceIn(0f, 1f) else 0f

                        if (!senderConnected) {
                            // Same horizontal status-strip language as the Send screen's
                            // discovery state, instead of a big centered hero circle - the two
                            // screens now share one consistent "looking for a peer" idiom.
                            //
                            // hostHasPeer vs senderConnected: hostHasPeer fires the instant a
                            // device joins this one's Wi-Fi Direct group at the P2P layer -
                            // well before any TCP connection exists. senderConnected only
                            // flips once a real socket is actually accepted, which is much
                            // later (the other device still has to open the handshake
                            // connection, both people confirm the match code, then the real
                            // transfer connection opens). Without watching hostHasPeer
                            // separately, this screen kept showing a bare "waiting" state
                            // through that whole gap - while the OTHER device's own screen
                            // already shows itself as connected the instant the P2P link
                            // forms. Two phones side by side visibly disagreeing about
                            // whether they were connected. This is the fix for that.
                            val linked = hostHasPeer
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (!linked) {
                                        RadarPulseRing(76, 0)
                                        RadarPulseRing(58, 700)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(if (linked) SleekSecondaryContainer else SleekPrimaryContainer)
                                            .border(1.5.dp, (if (linked) SleekSecondary else SleekPrimary).copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (linked) {
                                            CircularProgressIndicator(color = SleekSecondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                        } else {
                                            Icon(com.willyshare.willykez.ui.PulseIcons.SignalBars, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        if (linked) "Device linked" else "Waiting to receive",
                                        fontSize = 16.sp, fontWeight = FontWeight.Black, color = SleekOnSurface
                                    )
                                    Text(
                                        if (linked) "Starting transfer\u2026" else "Visible as \u201C$deviceName\u201D",
                                        fontSize = 11.sp, color = SleekOnSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (linked) {
                                    "Connected to a nearby device \u2014 hang tight while the transfer starts."
                                } else {
                                    "Ask the sender to pick \u201C$deviceName\u201D from their Send screen,\nor scan their QR code."
                                },
                                fontSize = 13.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center
                            )
                        } else {
                        Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                                Canvas(modifier = Modifier.size(180.dp)) {
                                    drawCircle(color = outlineColor.copy(alpha = 0.25f), style = Stroke(width = 14.dp.toPx()))
                                    drawArc(
                                        brush = Brush.sweepGradient(listOf(primaryColor, secondaryColor, primaryColor)),
                                        startAngle = -90f, sweepAngle = fraction * 360f, useCenter = false,
                                        style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${(fraction * 100).toInt()}%", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = SleekPrimary)
                                    Text("${formatBytes(progress.overallSpeed.toLong())}/s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SleekOnSurfaceVariant)
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = when {
                                progress.isComplete -> "Transfer complete"
                                progress.overallTotal > 0 -> "Receiving\u2026"
                                else -> "Connected"
                            },
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (progress.overallTotal == 0L) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E7D32))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Sender connected \u2014 waiting for files\u2026",
                                    fontSize = 13.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center
                                )
                            }
                        }
                        }

                        progress.error?.let {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(com.willyshare.willykez.ui.PulseIcons.Warning, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(it, fontSize = 12.sp, color = Color(0xFFD32F2F))
                            }
                        }

                        if (progress.files.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            GlassCard(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                Text("Files", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(progress.files, key = { it.key }) { item ->
                                        FileProgressRow(item, modifier = Modifier.animateItem())
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(if (!senderConnected) 90.dp else 20.dp))
                    }
                }

                // Sharing isn't meant to be a one-way street: once a peer is known (they've
                // linked to us, whether or not a transfer is actively running right now),
                // let this device push files back to them too - reusing the exact same
                // SelectFiles -> Transfer push path Send already uses. Hidden mid-transfer
                // only, so it can't collide with an in-progress receive.
                val canSendBack = connectedPeerIp != null && (progress.overallTotal == 0L || progress.isComplete)

                if (!senderConnected && connectedPeerIp == null && permissionsState.allPermissionsGranted) {
                    SleekFloatingPillButton(
                        text = "Scan QR",
                        icon = Icons.Default.QrCodeScanner,
                        onClick = { showScanSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                } else if (canSendBack) {
                    SleekFloatingPillButton(
                        text = "Send files too",
                        icon = com.willyshare.willykez.ui.PulseIcons.Send,
                        onClick = {
                            viewModel.prepareSendToConnectedPeer()
                            onNavigate("select")
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }

    if (showScanSheet) {
        ScanQrBottomSheet(
            viewModel = viewModel,
            onDismiss = { showScanSheet = false },
            onNavigate = onNavigate
        )
    }
}
