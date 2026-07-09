package com.willyshare.willykez.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import com.willyshare.willykez.ui.theme.CyanBright
import com.willyshare.willykez.ui.theme.SleekCard
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.VioletAccent
import kotlinx.coroutines.delay

private const val CONFIRM_WINDOW_MS = 30_000
private const val CONFIRM_TICK_MS = 100

/**
 * Global, screen-agnostic overlay for Stage 4's pre-transfer match-code confirmation.
 * Mount this once at the root (see MainActivity), on top of everything else - it renders
 * nothing when [PulseViewModel.pinConfirmation] is null, and blocks touches through to
 * whatever screen is underneath while a prompt is showing, on both the dialing and the
 * accepting device.
 */
@Composable
fun PinConfirmationOverlay(viewModel: PulseViewModel) {
    val pending by viewModel.pinConfirmation.collectAsState()
    val haptics = LocalHapticFeedback.current

    AnimatedVisibility(
        visible = pending != null,
        enter = fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.92f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.94f),
    ) {
        val request = pending ?: return@AnimatedVisibility
        LaunchedEffect(request.pin) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                    // Swallow taps on the scrim - don't let them fall through to the screen below.
                },
            contentAlignment = Alignment.Center
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
                    .widthIn(max = 340.dp)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(SleekCard.copy(alpha = 0.92f))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(listOf(VioletAccent.copy(alpha = glow), CyanBright.copy(alpha = glow))),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(VioletAccent, CyanBright))),
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
                    text = "Confirm this connection",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekOnSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(request.peerLabel)
                        append(if (request.isPullIntent) " wants to pull your files" else " wants to send you files")
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
                        text = "Make sure this code matches on both devices",
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
                            .background(Brush.linearGradient(listOf(VioletAccent, CyanBright)))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                viewModel.respondToPinConfirmation(true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Confirm", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
