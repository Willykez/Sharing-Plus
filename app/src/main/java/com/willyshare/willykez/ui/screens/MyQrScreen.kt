package com.willyshare.willykez.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.net.QrPairing
import com.willyshare.willykez.ui.AuroraBackground
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.theme.SleekBg
import com.willyshare.willykez.ui.theme.SleekCard
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary

/**
 * Bottom-sheet form of the QR screen - this is what Send now actually opens. Showing a QR is
 * a quick, dismissible action (glance at the code, wait, done), not a destination someone
 * navigates deep into, so a sheet that slides up over Send fits it better than a full page
 * with its own back stack entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQrBottomSheet(
    viewModel: PulseViewModel,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
) {
    val senderConnected by viewModel.senderConnected.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshMyQrPayload() }
    LaunchedEffect(senderConnected) {
        if (senderConnected) {
            onDismiss()
            onNavigate("transfer")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SleekBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("My Pairing QR", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
            Spacer(modifier = Modifier.height(16.dp))
            MyQrContent(viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Full-page form, kept for the "my_qr" route in case something still deep-links to it directly. */
@Composable
fun MyQrScreen(viewModel: PulseViewModel, onNavigate: (String) -> Unit) {
    val senderConnected by viewModel.senderConnected.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshMyQrPayload() }
    LaunchedEffect(senderConnected) { if (senderConnected) onNavigate("transfer") }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        AuroraBackground(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                InPageHeader(title = "My Pairing QR", showBack = true, onBack = { onNavigate("send") })
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    MyQrContent(viewModel = viewModel)
                }
            }
        }
    }
}

/** Shared by both the sheet and the full-page fallback - the QR itself, status text, and the
 *  high-speed mode toggle. No Scaffold/background/header here; callers provide those. */
@Composable
private fun MyQrContent(viewModel: PulseViewModel) {
    val payload by viewModel.myQrPayload.collectAsState()
    val senderConnected by viewModel.senderConnected.collectAsState()
    val deviceName by viewModel.thisDeviceName.collectAsState()
    val highSpeedMode by viewModel.highSpeedMode.collectAsState()
    val fastConnectStatus by viewModel.fastConnectStatus.collectAsState()
    val primaryDynamic = SleekPrimary

    val currentPayload = payload
    if (currentPayload == null) {
        Text(
            text = "Couldn't determine this device's local IP address.\nMake sure Wi-Fi is connected, then retry.",
            fontSize = 14.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.refreshMyQrPayload() },
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
        ) { Text("Retry", color = Color.White, fontWeight = FontWeight.Bold) }
    } else {
        val qrBitmap by produceState(initialValue = null as android.graphics.Bitmap?, currentPayload) {
            value = QrPairing.generateQrBitmap(currentPayload)
        }

        Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            // Corner brackets - a scanner-viewfinder motif instead of a plain frame,
            // reinforcing "this is meant to be scanned" at a glance.
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val len = 26.dp.toPx()
                val stroke = 3.dp.toPx()
                val inset = 4.dp.toPx()
                val w = size.width; val h = size.height
                val corners = listOf(
                    Triple(Offset(inset, inset), Offset(inset + len, inset), Offset(inset, inset + len)),
                    Triple(Offset(w - inset, inset), Offset(w - inset - len, inset), Offset(w - inset, inset + len)),
                    Triple(Offset(inset, h - inset), Offset(inset + len, h - inset), Offset(inset, h - inset - len)),
                    Triple(Offset(w - inset, h - inset), Offset(w - inset - len, h - inset), Offset(w - inset, h - inset - len))
                )
                corners.forEach { (corner, horiz, vert) ->
                    drawLine(color = primaryDynamic, start = corner, end = horiz, strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(color = primaryDynamic, start = corner, end = vert, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                qrBitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = "Pairing QR code", modifier = Modifier.fillMaxSize())
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(deviceName, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (senderConnected) "\u2705 Receiver connected \u2014 sending\u2026" else "Have the receiver open Sharing Plus \u2192 Receive \u2192 Scan QR",
            fontSize = 13.sp,
            color = if (senderConnected) Color(0xFF2E7D32) else SleekOnSurfaceVariant,
            textAlign = TextAlign.Center
        )
        fastConnectStatus?.let { status ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(status, fontSize = 11.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center)
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    if (viewModel.isFastConnectSupported) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SleekCard)
                .drawBehind {
                    drawLine(
                        color = primaryDynamic.copy(alpha = if (highSpeedMode) 0.7f else 0.2f),
                        start = Offset(size.width * 0.05f, 0f),
                        end = Offset(size.width * 0.35f, 0f),
                        strokeWidth = 2.5.dp.toPx()
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = if (highSpeedMode) SleekPrimary else SleekOnSurfaceVariant)
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("High-speed Mode", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                Text(
                    "Creates a dedicated 5GHz link between the two devices",
                    fontSize = 11.sp, color = SleekOnSurfaceVariant
                )
            }
            Switch(
                checked = highSpeedMode,
                onCheckedChange = { viewModel.setHighSpeedMode(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = SleekPrimary, checkedTrackColor = SleekPrimary.copy(alpha = 0.5f))
            )
        }
    } else {
        Text(
            "High-speed mode needs Android 10 or newer on this device.",
            fontSize = 11.sp, color = SleekOnSurfaceVariant, textAlign = TextAlign.Center
        )
    }
}