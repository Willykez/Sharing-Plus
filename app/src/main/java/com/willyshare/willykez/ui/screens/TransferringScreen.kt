package com.willyshare.willykez.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.ui.AuroraBackground
import com.willyshare.willykez.ui.FileProgressRow
import com.willyshare.willykez.ui.GlassCard
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.formatBytes
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.SleekSecondary

@Composable
fun TransferringScreen(viewModel: PulseViewModel, onNavigate: (String) -> Unit) {
    val progress by viewModel.sendProgress.collectAsState()
    val targetName by viewModel.targetName.collectAsState()

    LaunchedEffect(Unit) {
        // targetIp is only set when THIS device dialed out (peer-list connect, or scanning
        // someone's QR in the old push sense). If it's null, we landed here because someone
        // scanned OUR QR and is now pulling from us - that push is already running on its own
        // background thread (see PulseViewModel.handleIncomingPullRequest) the instant they
        // connected, and feeds the same sendProgress this screen already watches below. Calling
        // startTransferSession() in that case would wrongly fail immediately (no target) and
        // bounce back to the file picker.
        if (viewModel.targetIp.value != null) {
            viewModel.startTransferSession { success -> onNavigate(if (success) "dashboard" else "select") }
        }
    }

    // Passive case only: once a pull-triggered push finishes, there's no onComplete callback
    // to react to (see the known limitation noted on handleIncomingPullRequest), so watch
    // progress directly instead.
    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete && viewModel.targetIp.value == null) onNavigate("dashboard")
    }

    val fraction = if (progress.overallTotal > 0) (progress.overallBytes.toFloat() / progress.overallTotal.toFloat()).coerceIn(0f, 1f) else 0f

    // Rolling speed history for the sparkline below the ring - samples the current speed
    // twice a second and keeps the most recent ~30 seconds of it. This is purely a client-side
    // visualization; it doesn't touch the transfer itself.
    val speedHistory = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    LaunchedEffect(progress.isComplete) {
        while (!progress.isComplete) {
            speedHistory.add(progress.overallSpeed.toFloat())
            if (speedHistory.size > 60) speedHistory.removeAt(0)
            kotlinx.coroutines.delay(500)
        }
    }

    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        AuroraBackground(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                InPageHeader(title = "Transferring", showBack = true, onBack = { onNavigate("dashboard") })
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    val isPassivePull = viewModel.targetIp.value == null
                    Text(
                        text = if (isPassivePull) "Hide" else "Cancel",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F),
                        modifier = Modifier.clickable {
                            // Passive case (someone pulled from us): there's no transferJob to
                            // cancel - the push is running on FileReceiveServer's own thread and
                            // will finish regardless. "Hide" just leaves this screen instead of
                            // pretending Cancel stopped it; sendProgress keeps updating either way.
                            if (!isPassivePull) viewModel.cancelTransferSession()
                            onNavigate("dashboard")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (progress.isComplete) "Transfer complete" else "Sending over Wi-Fi\u2026",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${progress.files.size} file${if (progress.files.size != 1) "s" else ""} to ${targetName ?: "device"}",
                    fontSize = 14.sp, color = SleekOnSurfaceVariant
                )

                val outlineColor = SleekOutline
                val primaryColor = SleekPrimary
                val secondaryColor = SleekSecondary
                val successColor = Color(0xFF2E7D32)
                val ringPulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "ring_pulse")
                val ringGlow by ringPulse.animateFloat(
                    initialValue = 0.5f, targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "ringGlow"
                )
                Box(modifier = Modifier.size(220.dp).padding(20.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(180.dp)) {
                        drawCircle(color = outlineColor.copy(alpha = 0.25f), style = Stroke(width = 14.dp.toPx()))
                        if (progress.isComplete) {
                            drawCircle(color = successColor, style = Stroke(width = 14.dp.toPx()))
                        } else {
                            drawArc(
                                brush = Brush.sweepGradient(listOf(primaryColor, secondaryColor, primaryColor)),
                                startAngle = -90f, sweepAngle = fraction * 360f, useCenter = false,
                                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                            )
                            // A faint pulsing ring just outside the progress arc's leading
                            // edge - a small "still actively moving" signal beyond the arc
                            // itself, most noticeable on long, slow-moving transfers.
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.12f * ringGlow),
                                radius = size.minDimension / 2f + 10.dp.toPx(),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                    if (progress.isComplete) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(350, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.5f) +
                                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250))
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Complete",
                                tint = successColor,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(fraction * 100).toInt()}%", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = SleekPrimary)
                            Text("${formatBytes(progress.overallSpeed.toLong())}/s", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SleekOnSurfaceVariant)
                            val remainingBytes = (progress.overallTotal - progress.overallBytes).coerceAtLeast(0)
                            val etaSeconds = if (progress.overallSpeed > 0) (remainingBytes / progress.overallSpeed).toLong() else -1L
                            if (etaSeconds in 1..3599) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (etaSeconds < 60) "$etaSeconds sec left" else "${etaSeconds / 60} min left",
                                    fontSize = 10.sp, color = SleekOnSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                if (!progress.isComplete && speedHistory.size >= 3) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SpeedSparkline(samples = speedHistory, modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(28.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                }

                progress.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(com.willyshare.willykez.ui.PulseIcons.Warning, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(it, fontSize = 12.sp, color = Color(0xFFD32F2F))
                    }
                }

                if (progress.files.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).weight(1f, fill = false)) {
                        Text("Files", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(progress.files, key = { it.key }) { item ->
                                FileProgressRow(item, modifier = Modifier.animateItem())
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SpeedSparkline(samples: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = SleekPrimary
    val fillColor = SleekPrimary
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val maxSpeed = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
        val points = samples.mapIndexed { i, v ->
            androidx.compose.ui.geometry.Offset(i * stepX, size.height - (v / maxSpeed) * size.height)
        }
        val linePath = androidx.compose.ui.graphics.Path().apply {
            moveTo(points.first().x, points.first().y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        val fillPath = androidx.compose.ui.graphics.Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(fillPath, brush = Brush.verticalGradient(listOf(fillColor.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(linePath, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}