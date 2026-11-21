package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.JarvisPhase
import com.example.R
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcIce
import com.example.ui.theme.ReactorWhite
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArcReactorCore(
    phase: JarvisPhase,
    voiceRmsDb: Float,
    isLiveSessionActive: Boolean = false,
    isAutoSleepStandby: Boolean = false,
    onTapCore: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 216.dp
) {
    val primaryColor by animateColorAsState(
        targetValue = when {
            phase == JarvisPhase.LISTENING -> ArcIce
            phase == JarvisPhase.THINKING -> StarkAmber
            phase == JarvisPhase.ACTING -> SignalGreen
            phase == JarvisPhase.SPEAKING -> ArcCyan
            isAutoSleepStandby -> StarkAmber
            else -> ArcCyan
        },
        animationSpec = tween(durationMillis = 280),
        label = "reactor_primary_color"
    )

    val phaseLabel = when (phase) {
        JarvisPhase.IDLE -> stringResource(R.string.state_idle)
        JarvisPhase.LISTENING -> stringResource(R.string.state_listening)
        JarvisPhase.THINKING -> stringResource(R.string.state_thinking)
        JarvisPhase.ACTING -> stringResource(R.string.state_acting)
        JarvisPhase.SPEAKING -> stringResource(R.string.state_speaking)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor_motion")
    val rotationDuration = when (phase) {
        JarvisPhase.IDLE -> 16_000
        JarvisPhase.LISTENING -> 6_000
        JarvisPhase.THINKING -> 2_600
        JarvisPhase.ACTING -> 3_400
        JarvisPhase.SPEAKING -> 5_000
    }
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = rotationDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_ring_angle"
    )
    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (rotationDuration * 0.72f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_ring_angle"
    )
    val breathPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (phase == JarvisPhase.IDLE) 2400 else 850,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_breath_pulse"
    )
    val rmsBoost by animateFloatAsState(
        targetValue = (voiceRmsDb / 12f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "rms_boost"
    )

    val tapDescription = stringResource(R.string.cd_arc_reactor)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .testTag("arc_reactor_button")
            .semantics { contentDescription = tapDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = primaryColor),
                role = Role.Button,
                onClick = onTapCore
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = minOf(this.size.width, this.size.height) / 2f
            val pulseScale = breathPulse + (rmsBoost * 0.08f)

            // Ambient radial halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.36f + rmsBoost * 0.2f),
                        primaryColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius * pulseScale.coerceAtMost(1.0f)
                ),
                radius = maxRadius,
                center = center
            )

            // Outer precision telemetry ring with 24 ticks
            val outerRingR = maxRadius * 0.90f
            drawCircle(
                color = primaryColor.copy(alpha = 0.25f),
                radius = outerRingR,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            for (i in 0 until 36) {
                val rad = (i * 10.0) * (PI / 180.0)
                val innerTick = if (i % 3 == 0) outerRingR - 8.dp.toPx() else outerRingR - 4.dp.toPx()
                val sx = center.x + (cos(rad) * innerTick).toFloat()
                val sy = center.y + (sin(rad) * innerTick).toFloat()
                val ex = center.x + (cos(rad) * outerRingR).toFloat()
                val ey = center.y + (sin(rad) * outerRingR).toFloat()
                drawLine(
                    color = primaryColor.copy(alpha = if (i % 3 == 0) 0.72f else 0.30f),
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Rotating segmented outer arc coils
            val coilRadius = maxRadius * 0.76f
            val coilTopLeft = Offset(center.x - coilRadius, center.y - coilRadius)
            val coilSize = Size(coilRadius * 2f, coilRadius * 2f)
            rotate(degrees = outerAngle, pivot = center) {
                for (segment in 0 until 6) {
                    drawArc(
                        color = primaryColor.copy(alpha = 0.85f),
                        startAngle = segment * 60f + 8f,
                        sweepAngle = 40f,
                        useCenter = false,
                        topLeft = coilTopLeft,
                        size = coilSize,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Butt)
                    )
                }
            }

            // Counter-rotating inner orbital reticle
            val innerRadius = maxRadius * 0.60f
            val innerTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
            val innerSize = Size(innerRadius * 2f, innerRadius * 2f)
            rotate(degrees = innerAngle, pivot = center) {
                for (segment in 0 until 3) {
                    drawArc(
                        color = ReactorWhite.copy(alpha = 0.78f),
                        startAngle = segment * 120f + 14f,
                        sweepAngle = 82f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Inner glowing reactor nucleus
            val nucleusRadius = maxRadius * 0.43f * (0.94f + rmsBoost * 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ReactorWhite.copy(alpha = 0.92f),
                        primaryColor.copy(alpha = 0.65f),
                        primaryColor.copy(alpha = 0.18f)
                    ),
                    center = center,
                    radius = nucleusRadius
                ),
                radius = nucleusRadius,
                center = center
            )

            // Dark inner plate for crisp status typography
            drawCircle(
                color = Color(0xCC040915),
                radius = maxRadius * 0.36f,
                center = center
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.9f),
                radius = maxRadius * 0.36f,
                center = center,
                style = Stroke(width = 1.8.dp.toPx())
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = primaryColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when {
                    isLiveSessionActive -> "TAP STOP"
                    isAutoSleepStandby && phase == JarvisPhase.IDLE -> "AUTO-SLEEP"
                    else -> "TAP LIVE"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
