package com.ultrayo.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object UltraColors {
    val Accent = Color(0xFF4FC3F7)
    val AccentBright = Color(0xFF00E5FF)
    val Deep = Color(0xFF050A14)
    val Navy = Color(0xFF0A1F33)
    val Glass = Color(0x14FFFFFF)
    val GlassBorder = Color(0x404FC3F7)
    val Text = Color(0xFFE8F4FA)
    val Muted = Color(0xFF8AA4B5)
    val UserA = Color(0xFF1565C0)
    val UserB = Color(0xFF0288D1)
}

val Background = Brush.verticalGradient(listOf(UltraColors.Deep, UltraColors.Navy, UltraColors.Deep))

/** Panel translúcido con borde suave, estilo cristal. */
fun Modifier.glass(radius: Dp = 20.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(UltraColors.Glass)
    .border(1.dp, UltraColors.GlassBorder, RoundedCornerShape(radius))

/**
 * El "reactor": un núcleo luminoso con anillos, como el de Iron Man.
 * Late cuando escucha y gira cuando piensa.
 */
@Composable
fun Reactor(size: Dp, listening: Boolean = false, thinking: Boolean = false, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reactor")
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (thinking) 1200 else 9000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (listening) 450 else 1800), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        val glow = if (listening) pulse else 0.9f
        drawCircle(
            Brush.radialGradient(
                listOf(UltraColors.AccentBright.copy(alpha = 0.55f * glow), Color.Transparent),
                center = c, radius = r,
            ),
            radius = r,
        )
        drawCircle(UltraColors.Accent.copy(alpha = 0.35f), radius = r * 0.92f, style = Stroke(r * 0.03f))
        val arcSize = Size(r * 1.5f, r * 1.5f)
        val arcTopLeft = Offset(c.x - arcSize.width / 2, c.y - arcSize.height / 2)
        for (i in 0 until 3) {
            drawArc(
                color = UltraColors.Accent.copy(alpha = if (thinking) 0.95f else 0.7f),
                startAngle = rotation + i * 120f, sweepAngle = 70f, useCenter = false,
                topLeft = arcTopLeft, size = arcSize, style = Stroke(r * 0.07f),
            )
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, UltraColors.AccentBright, UltraColors.Accent.copy(alpha = 0.2f)),
                center = c, radius = r * 0.45f * glow,
            ),
            radius = r * 0.45f * glow,
        )
    }
}

/** Botón principal con degradado. */
@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.horizontalGradient(listOf(UltraColors.UserA, UltraColors.AccentBright)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Título de sección dentro de los paneles. */
@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title.uppercase(), color = UltraColors.Accent, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = UltraColors.Muted, fontSize = 13.sp)
    }
}
