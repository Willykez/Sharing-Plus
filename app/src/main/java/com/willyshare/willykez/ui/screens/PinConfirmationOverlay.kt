package com.willyshare.willykez.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.theme.SleekBg
import com.willyshare.willykez.ui.theme.SleekSecondary
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import kotlinx.coroutines.delay

private const val CONFIRM_WINDOW_MS = 30_000
private const val CONFIRM_TICK_MS = 100

/**
 * Global, screen-agnostic bottom sheet for Stage 4's pre-transfer match-code confirmation.
 * Mount this once at the root (see MainActivity), on top of everything else - it renders
 * nothing when [PulseViewModel.pinConfirmation] is null. A bottom sheet instead of a centered
 * dialog: it's within thumb reach on a phone, matches the reference Quick Share screenshot's
 * own bottom-anchored card, and standard swipe-to-dismiss maps onto Decline/Cancel instead of
 * silently leaving the prompt hanging until it times out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinConfirmationOverlay(viewModel: PulseViewModel) {
    val pending by viewModel.pinConfirmation.collectAsState()
    val haptics = LocalHapticFeedback.current
    val request = pending ?: return

    LaunchedEffect(request.pin) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            // Swiped away without tapping a button - treat exactly like the explicit action
            // for this side, so nothing is left silently waiting on a dismissed sheet.
            if (request.isIncoming) viewModel.respondToPinConfirmation(false) else viewModel.cancelPendingHandshake()
        },
        sheetState = sheetState,
        containerColor = SleekBg,
        dragHandle = null
    ) {
        var remainingMs by remember(request.pin) { mutableFloatStateOf(CONFIRM_WINDOW_MS.toFloat()) }
        LaunchedEffect(request.pin) {
            while (remainingMs > 0) {
                delay(CONFIRM_TICK_MS.toLong())
                remainingMs -= CONFIRM_TICK_MS
            }
        }
        val progressFraction = (remainingMs / CONFIRM_WINDOW_MS).coerceIn(0f, 1f)

        val pulse = rememberInfiniteTransition(label = "pin_pulse")
        val glow by pulse.animateFloat(
            initialValue = 0.35f, targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "glow"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // A short grab-bar tinted with the live theme color instead of the sheet default -
            // small touch, but it's the first pixel someone sees, and it now matches everything else.
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SleekPrimary.copy(alpha = 0.4f))
            )
            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(SleekPrimary, SleekSecondary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (request.isPullIntent) Icons.Filled.CloudDownload else Icons.Filled.CloudUpload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (request.isIncoming) "Confirm this connection" else "Waiting for confirmation\u2026",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SleekOnSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (request.isIncoming) {
                    buildString {
                        append(request.peerLabel)
                        append(if (request.isPullIntent) " wants to pull your files" else " wants to send you files")
                    }
                } else {
                    "Ask ${request.peerLabel} to confirm this code matches on their screen"
                },
                fontSize = 13.sp,
                color = SleekOnSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            // The code itself - big, spaced digits in individual glass cells so it reads
            // instantly at a glance and is easy to compare against the other device.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                request.pin.forEach { digit ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SleekOutline.copy(alpha = 0.12f))
                            .border(1.dp, SleekPrimary.copy(alpha = glow), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = digit.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = SleekOnSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = SleekOnSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (request.isIncoming) "Make sure this code matches on both devices" else "Waiting for them to confirm this same code",
                    fontSize = 11.sp,
                    color = SleekOnSurfaceVariant
                )
            }

            Spacer(Modifier.height(18.dp))

            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = if (progressFraction < 0.25f) Color(0xFFD32F2F) else SleekPrimary,
                trackColor = SleekOutline.copy(alpha = 0.15f),
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (request.isIncoming) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFFD32F2F).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                viewModel.respondToPinConfirmation(false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Decline", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(SleekPrimary, SleekSecondary)))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                viewModel.respondToPinConfirmation(true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Confirm", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                } else {
                    // Dialer side: nothing to confirm here - this device already chose its
                    // target deliberately. Just a way out if they change their mind.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, SleekOutline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                viewModel.cancelPendingHandshake()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancel", color = SleekOnSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
