package com.willyshare.willykez.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import com.willyshare.willykez.ui.theme.*
import com.willyshare.willykez.net.FileProgressItem

@Composable
fun SleekTopBar(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    rightIcon: ImageVector? = null,
    onRightClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SleekSurfaceContainer)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, SleekOutline.copy(alpha = 0.4f), CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SleekOnSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(SleekPrimaryContainer)
                        .border(1.dp, SleekPrimary.copy(alpha = 0.3f), MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(PulseIcons.Brand, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column {
                Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 11.sp, color = SleekOnSurfaceVariant)
                }
            }
        }

        if (rightIcon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, SleekOutline.copy(alpha = 0.4f), CircleShape)
                    .clickable { onRightClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = rightIcon, contentDescription = "Action", tint = SleekOnSurface, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A page header meant to live inside the scrollable page's own Column, above the
 * LazyColumn, rather than as a Scaffold topBar. It has no background fill and no
 * elevation/shadow, so there's no visible seam or divider line between it and the
 * page content below - it reads as part of the page, not a separate bar docked on top.
 */
@Composable
fun InPageHeader(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    rightIcon: ImageVector? = null,
    onRightClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SleekSurfaceContainer)
                        .border(1.dp, SleekOutline.copy(alpha = 0.4f), CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SleekOnSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(SleekPrimaryContainer)
                        .border(1.dp, SleekPrimary.copy(alpha = 0.3f), MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(PulseIcons.Brand, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column {
                Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 11.sp, color = SleekOnSurfaceVariant)
                }
            }
        }

        if (rightIcon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SleekSurfaceContainer)
                    .border(1.dp, SleekOutline.copy(alpha = 0.4f), CircleShape)
                    .clickable { onRightClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = rightIcon, contentDescription = "Action", tint = SleekOnSurface, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun SleekBottomNav(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    data class NavItem(val route: String, val label: String, val icon: ImageVector, val filledIcon: ImageVector)

    val items = remember {
        listOf(
            NavItem("dashboard", "Home", Icons.Outlined.Home, Icons.Filled.Home),
            NavItem("history", "History", Icons.Outlined.History, Icons.Filled.History),
            NavItem("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
        )
    }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .height(64.dp)
                    .shadow(elevation = 10.dp, shape = PillShape, clip = false)
                    .clip(PillShape)
                    .background(SleekSurfaceContainer)
                    .border(1.dp, SleekOutline.copy(alpha = 0.25f), PillShape)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            items.forEach { navItem ->
                val selected = navItem.route == currentRoute
                FloatingNavTabItem(
                    label = navItem.label,
                    icon = if (selected) navItem.filledIcon else navItem.icon,
                    selected = selected,
                    onClick = { onNavigate(navItem.route) },
                )
            }
        }
    }
}

/**
 * A single nav pill item. When selected, it bounces open to reveal its label using a
 * medium-bouncy spring - matching the "page bouncing" motion feel of the reference nav bar -
 * and swaps to a filled icon. Unselected items are icon-only circles.
 */
@Composable
private fun FloatingNavTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val labelWidth by animateDpAsState(
        targetValue = if (selected) 72.dp else 0.dp,
        animationSpec =
            reducedMotionAwareSpec(
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
        label = "nav_label_width",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) SleekSecondary else Color.Transparent,
        animationSpec = reducedMotionAwareSpec(spring()),
        label = "nav_item_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) SleekOnSecondary else SleekOnSurfaceVariant,
        animationSpec = reducedMotionAwareSpec(spring()),
        label = "nav_item_color",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec =
            reducedMotionAwareSpec(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            ),
        label = "nav_item_scale",
    )

    Box(
        modifier =
            Modifier
                .padding(horizontal = 3.dp)
                .scale(scale)
                .height(48.dp)
                .widthIn(min = 48.dp)
                .clip(PillShape)
                .background(containerColor)
                .clickable { onClick() }
                .padding(horizontal = if (selected) 14.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(22.dp))
            if (labelWidth > 4.dp) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.width(labelWidth - 6.dp),
                )
            }
        }
    }
}

/**
 * A floating pill-shaped action button anchored bottom-center, used for actions like
 * "Pair via QR" / "Show my QR" instead of a standard Material FAB. Uses the secondary
 * color role to visually differentiate from the primary-colored nav bar.
 */
@Composable
fun SleekFloatingPillButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(56.dp)
                .shadow(elevation = 8.dp, shape = PillShape, clip = false)
                .clip(PillShape)
                .background(SleekSecondary)
                .clickable { onClick() }
                .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = text, tint = SleekOnSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SleekOnSecondary)
    }
}

private val fabCircleShape = RoundedPolygon.circle(numVertices = 16)
private val fabStarShape =
    RoundedPolygon.star(
        numVerticesPerRadius = 8,
        innerRadius = 0.72f,
        rounding = CornerRounding(radius = 0.18f, smoothing = 0.1f),
    )
private val fabMorph = Morph(fabCircleShape, fabStarShape)

/**
 * The nav bar's "create" action - a circular FAB that morphs into a soft sunburst shape
 * (matching the reference app's Cookie->Sunny FAB morph, rebuilt on androidx.graphics.shapes
 * which is stable, rather than the Expressive-only MaterialShapes helpers) with its plus icon
 * rotating 135 degrees into an "x" as it presses. Tapping it starts a new Send flow.
 */
@Composable
fun SleekCreateFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val morphProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec =
            reducedMotionAwareSpec(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            ),
        label = "fab_morph_progress",
        finishedListener = { if (pressed) pressed = false },
    )
    val iconRotation by animateFloatAsState(
        targetValue = if (pressed) 135f else 0f,
        animationSpec =
            reducedMotionAwareSpec(
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            ),
        label = "fab_icon_rotation",
    )
    val fabShape =
        remember(morphProgress) {
            MorphComposeShape(fabMorph, morphProgress)
        }

    Box(
        modifier =
            modifier
                .size(64.dp)
                .shadow(elevation = 8.dp, shape = fabShape, clip = false)
                .clip(fabShape)
                .background(SleekPrimary)
                .clickable {
                    pressed = true
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "New transfer",
            tint = Color.White,
            modifier =
                Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = iconRotation },
        )
    }
}

/** Renders an androidx.graphics.shapes [Morph] at [progress] as a Compose [Shape]. */
private class MorphComposeShape(
    private val morph: Morph,
    private val progress: Float,
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline {
        val bounds = fabCircleShape.calculateBounds()
        val path = morph.toPath(progress = progress).asComposePath()
        val boundsWidth = (bounds[2] - bounds[0]).takeIf { it > 0f } ?: 2f
        val boundsHeight = (bounds[3] - bounds[1]).takeIf { it > 0f } ?: 2f
        val matrix = androidx.compose.ui.graphics.Matrix()
        matrix.scale(size.width / boundsWidth, size.height / boundsHeight)
        matrix.translate(-bounds[0], -bounds[1])
        path.transform(matrix)
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}



@Composable
fun RadarPulseRing(sizeDp: Int, delayMillis: Int = 0) {
    val transition = rememberInfiniteTransition(label = "radar")
    val scale = transition.animateFloat(
        initialValue = 0.8f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(2800, delayMillis, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale"
    ).value
    val alpha = transition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2800, delayMillis, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "alpha"
    ).value

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .scale(scale)
            .border(1.5.dp, SleekPrimary.copy(alpha = alpha), CircleShape)
    )
}

/**
 * The app's background signature - deliberately NOT the soft violet/cyan blur-blob look this
 * screen used to have (that was hardcoded to VioletAccent/CyanBright and ignored whichever of
 * the 8 curated palettes - or Material You, or a custom color - the person actually picked in
 * Settings). This draws a fine dot-grid mesh plus a diagonal scan-line sweep, entirely from the
 * live SleekPrimary/SleekSecondary tokens, so it's correct under every theme AND reads as a
 * distinct "radar/mesh" identity rather than ambient glow.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val transition = rememberInfiniteTransition(label = "mesh_field")
    val sweep by transition.animateFloat(
        initialValue = -0.3f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(6500, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val primary = SleekPrimary
    val secondary = SleekSecondary
    val bg = SleekBg
    val dotColor = SleekOutline

    Box(modifier = modifier.fillMaxSize().background(bg)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Fine dot-grid mesh - the structural signature replacing soft blur blobs.
            val spacing = 32.dp.toPx()
            var gy = spacing / 2
            while (gy < h) {
                var gx = spacing / 2
                while (gx < w) {
                    drawCircle(color = dotColor.copy(alpha = 0.09f), radius = 1.1.dp.toPx(), center = Offset(gx, gy))
                    gx += spacing
                }
                gy += spacing
            }

            // Two restrained corner glows in the ACTUAL theme colors, so Ember/Ocean/Iris/etc
            // all look correct here, not just whichever hue happened to be hardcoded before.
            drawCircle(
                brush = Brush.radialGradient(listOf(primary.copy(alpha = 0.22f * pulse), Color.Transparent), radius = w * 0.55f),
                radius = w * 0.55f,
                center = Offset(w * 0.06f, h * 0.04f)
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(secondary.copy(alpha = 0.18f), Color.Transparent), radius = w * 0.5f),
                radius = w * 0.5f,
                center = Offset(w * 0.96f, h * 0.92f)
            )

            // Diagonal scan sweep - a deliberate "radar pass" motion instead of ambient drift.
            val sweepX = w * sweep
            drawLine(
                brush = Brush.linearGradient(listOf(Color.Transparent, primary.copy(alpha = 0.30f), Color.Transparent)),
                start = Offset(sweepX, -h * 0.2f),
                end = Offset(sweepX - w * 0.35f, h * 1.2f),
                strokeWidth = w * 0.16f
            )
        }
        content()
    }
}

/**
 * A panel, not a pane of glass: solid surface, crisp 20dp corners, a thin theme-colored
 * accent hairline across the top-left instead of an all-around glow border. Deliberately a
 * different silhouette from the old fully-blurred, evenly-bordered GlassCard look.
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val accent = SleekPrimary
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(SleekSurfaceContainer)
            .border(1.dp, SleekOutline.copy(alpha = 0.16f), shape)
            .drawBehind {
                drawLine(
                    color = accent.copy(alpha = 0.6f),
                    start = Offset(size.width * 0.06f, 0f),
                    end = Offset(size.width * 0.5f, 0f),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
            .padding(16.dp),
        content = content
    )
}

@Composable
fun FileProgressRow(item: FileProgressItem, modifier: Modifier = Modifier) {
    val fraction = if (item.totalBytes > 0) (item.transferredBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val doneColor = Color(0xFF2E7D32)
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SleekOnSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (item.isComplete) "Done" else "${(fraction * 100).toInt()}%",
                fontSize = 12.sp, color = if (item.isComplete) doneColor else SleekOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
            color = if (item.isComplete) doneColor else SleekPrimary,
            trackColor = SleekOutline.copy(alpha = 0.2f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${formatBytes(item.transferredBytes)} / ${formatBytes(item.totalBytes)} \u00B7 ${formatBytes(item.speedBytesPerSec.toLong())}/s",
            fontSize = 10.sp, color = SleekOnSurfaceVariant
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}