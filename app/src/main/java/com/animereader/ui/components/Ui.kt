package com.animereader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animereader.ui.theme.LocalDirStyle
import com.animereader.ui.theme.LocalPalette
import com.animereader.ui.theme.LocalTextScale

/** Minimal line-icon set drawn with Canvas (currentColor = [tint]). */
@Composable
fun AppIcon(name: String, size: Dp = 22.dp, tint: Color = LocalPalette.current.text) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = w / 24f
        val sw = 2f * s
        val stroke = Stroke(width = sw, cap = StrokeCap.Round)
        fun p(block: Path.() -> Unit) = Path().apply(block)
        when (name) {
            "play" -> drawPath(p { moveTo(8f * s, 5f * s); lineTo(8f * s, 19f * s); lineTo(19f * s, 12f * s); close() }, tint)
            "target" -> {
                drawRoundRect(tint, topLeft = Offset(4.5f * s, 6.5f * s),
                    size = androidx.compose.ui.geometry.Size(15f * s, 11f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * s), style = stroke)
            }
            "moon" -> drawPath(p {
                moveTo(20f * s, 13.5f * s)
                cubicTo(14f * s, 16f * s, 8f * s, 11f * s, 10.5f * s, 4f * s)
                cubicTo(4f * s, 6f * s, 4f * s, 17f * s, 12f * s, 18f * s)
                cubicTo(16f * s, 18.4f * s, 19f * s, 16.5f * s, 20f * s, 13.5f * s)
                close()
            }, tint)
            "sun" -> {
                drawCircle(tint, radius = 4f * s, center = Offset(12f * s, 12f * s), style = stroke)
                val rays = listOf(12f to 2.5f, 12f to 21.5f, 4.2f to 4.2f, 19.8f to 19.8f, 2.5f to 12f, 21.5f to 12f, 4.2f to 19.8f, 19.8f to 4.2f)
                for ((x, y) in rays) drawLine(tint, Offset(12f * s, 12f * s), Offset(x * s, y * s), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "sliders" -> {
                drawLine(tint, Offset(5f * s, 7f * s), Offset(19f * s, 7f * s), sw, StrokeCap.Round)
                drawLine(tint, Offset(5f * s, 12f * s), Offset(19f * s, 12f * s), sw, StrokeCap.Round)
                drawLine(tint, Offset(5f * s, 17f * s), Offset(19f * s, 17f * s), sw, StrokeCap.Round)
                drawCircle(tint, 2.2f * s, Offset(16f * s, 7f * s)); drawCircle(tint, 2.2f * s, Offset(9f * s, 12f * s)); drawCircle(tint, 2.2f * s, Offset(13f * s, 17f * s))
            }
            "refresh" -> {
                drawArc(tint, -30f, -210f, false, topLeft = Offset(4f * s, 4f * s),
                    size = androidx.compose.ui.geometry.Size(16f * s, 16f * s), style = stroke)
            }
            "close" -> {
                drawLine(tint, Offset(6f * s, 6f * s), Offset(18f * s, 18f * s), sw, StrokeCap.Round)
                drawLine(tint, Offset(18f * s, 6f * s), Offset(6f * s, 18f * s), sw, StrokeCap.Round)
            }
            "sparkle" -> drawPath(p {
                moveTo(12f * s, 4f * s); lineTo(13.6f * s, 10.4f * s); lineTo(20f * s, 12f * s)
                lineTo(13.6f * s, 13.6f * s); lineTo(12f * s, 20f * s); lineTo(10.4f * s, 13.6f * s)
                lineTo(4f * s, 12f * s); lineTo(10.4f * s, 10.4f * s); close()
            }, tint)
            "headphones" -> {
                drawArc(tint, 180f, 180f, false, topLeft = Offset(5f * s, 6f * s),
                    size = androidx.compose.ui.geometry.Size(14f * s, 14f * s), style = stroke)
                drawRoundRect(tint, topLeft = Offset(4f * s, 12f * s),
                    size = androidx.compose.ui.geometry.Size(3f * s, 6f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * s))
                drawRoundRect(tint, topLeft = Offset(17f * s, 12f * s),
                    size = androidx.compose.ui.geometry.Size(3f * s, 6f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * s))
            }
            "check" -> drawPath(p { moveTo(5f * s, 12.5f * s); lineTo(9.5f * s, 17f * s); lineTo(19f * s, 7f * s) }, tint, style = stroke)
            "chevron" -> drawPath(p { moveTo(6f * s, 9.5f * s); lineTo(12f * s, 15.5f * s); lineTo(18f * s, 9.5f * s) }, tint, style = stroke)
            else -> drawCircle(tint, 3f * s, Offset(12f * s, 12f * s))
        }
    }
}

@Composable
fun fs(base: Float): androidx.compose.ui.unit.TextUnit = (base * LocalTextScale.current).sp

@Composable
fun PrimaryButton(label: String, icon: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(pal.accent, RoundedCornerShape(style.btnRadius))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { AppIcon(icon, 18.dp, pal.onAccent); androidx.compose.foundation.layout.Spacer(Modifier.size(9.dp)) }
        Text(label, color = pal.onAccent, fontSize = fs(15f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(label: String, icon: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.5.dp, pal.border, RoundedCornerShape(style.btnRadius))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { AppIcon(icon, 17.dp, pal.text); androidx.compose.foundation.layout.Spacer(Modifier.size(9.dp)) }
        Text(label, color = pal.text, fontSize = fs(15f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DangerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    val danger = Color(0xFFE5484D)
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.5.dp, danger.copy(alpha = 0.35f), RoundedCornerShape(style.btnRadius))
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon("close", 17.dp, danger); androidx.compose.foundation.layout.Spacer(Modifier.size(9.dp))
        Text(label, color = danger, fontSize = fs(15f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CircleIconButton(icon: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .size(42.dp)
            .background(pal.surface2, CircleShape)
            .border(1.5.dp, pal.border, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { AppIcon(icon, 18.dp, pal.textMuted) }
}

@Composable
fun ARSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .size(width = 50.dp, height = 30.dp)
            .background(if (checked) pal.accent else pal.border, CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(24.dp).background(Color.White, CircleShape))
    }
}
