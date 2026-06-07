package com.animereader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.animereader.data.AppMode
import com.animereader.data.ThemeDirection

/** Design tokens for one direction × mode. Ported 1:1 from theme.jsx. */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val elev: Color,
    val border: Color, val text: Color, val textMuted: Color,
    val accent: Color, val accentSoft: Color, val accentBorder: Color, val onAccent: Color,
    val offline: Color, val online: Color,
    val page: Color, val panel: Color, val panelInk: Color,
)

@Immutable
data class DirectionStyle(
    val radius: Dp, val radiusSm: Dp, val radiusLg: Dp, val btnRadius: Dp,
    val displayFamily: FontFamily,
)

val LocalPalette = staticCompositionLocalOf { sumiLight }
val LocalDirStyle = staticCompositionLocalOf { sumiStyle }
val LocalTextScale = staticCompositionLocalOf { 1f }

/** Parse "#rgb", "#rrggbb" or "rgba(r,g,b,a)" → Compose Color. */
fun parseColor(s: String): Color {
    val v = s.trim()
    if (v.startsWith("rgba(") || v.startsWith("rgb(")) {
        val nums = v.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        val r = nums[0].toInt(); val g = nums[1].toInt(); val b = nums[2].toInt()
        val a = if (nums.size > 3) nums[3].toFloat() else 1f
        return Color(r / 255f, g / 255f, b / 255f, a)
    }
    var hex = v.removePrefix("#")
    if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
    val r = hex.substring(0, 2).toInt(16)
    val g = hex.substring(2, 4).toInt(16)
    val b = hex.substring(4, 6).toInt(16)
    return Color(r, g, b)
}

private fun p(
    bg: String, surface: String, surface2: String, elev: String, border: String,
    text: String, textMuted: String, accent: String, accentSoft: String, accentBorder: String,
    onAccent: String, offline: String, online: String, page: String, panel: String, panelInk: String,
) = Palette(
    parseColor(bg), parseColor(surface), parseColor(surface2), parseColor(elev), parseColor(border),
    parseColor(text), parseColor(textMuted), parseColor(accent), parseColor(accentSoft), parseColor(accentBorder),
    parseColor(onAccent), parseColor(offline), parseColor(online), parseColor(page), parseColor(panel), parseColor(panelInk),
)

// ── Sumi ──
// Accent = the brand logo's pink (#F85876, sampled from logo.png) so the Démarrer /
// "Revenir à la bulle" buttons, the floating bubble and the reading rectangle all match the logo.
val sumiLight = p(
    "#ece5d6", "#fbf7ed", "#f1e9d8", "#fffdf6", "#ddd2bd", "#1d1813", "#867c69",
    "#f85876", "rgba(248,88,118,0.12)", "rgba(248,88,118,0.40)", "#ffffff", "#3f7d3a", "#2f63a8",
    "#e7dcc6", "#fbf7ed", "#23201b",
)
// Sumi dark == Neon navy (per final design).
private val navyDark = p(
    "#0a0e1c", "#121830", "#1b2344", "#161d3a", "#2a3358", "#eef1fb", "#8b92b8",
    "#ff5e7a", "rgba(255,94,122,0.14)", "rgba(255,94,122,0.46)", "#ffffff", "#2ee6a6", "#b9a6ff",
    "#070a16", "#111734", "#eef1fb",
)
// Sumi dark uses the same navy surfaces but the exact logo pink as accent.
val sumiDark = navyDark.copy(
    accent = parseColor("#f85876"),
    accentSoft = parseColor("rgba(248,88,118,0.16)"),
    accentBorder = parseColor("rgba(248,88,118,0.48)"),
)

// ── Neon ──
val neonLight = p(
    "#eceef6", "#ffffff", "#f2f3fb", "#ffffff", "#e0e2ee", "#13131e", "#6b6d88",
    "#f5305b", "rgba(245,48,91,0.10)", "rgba(245,48,91,0.38)", "#ffffff", "#00a06a", "#7c4dff",
    "#e3e5f1", "#ffffff", "#13131e",
)
val neonDark = navyDark

// ── Material You ──
val materialLight = p(
    "#fdf7fe", "#ffffff", "#f4edf7", "#fffbfe", "#e6dfeb", "#1d1b20", "#49454f",
    "#6750a4", "#e9ddff", "rgba(103,80,164,0.4)", "#ffffff", "#3f6b1f", "#8a4dc6",
    "#f3ecf7", "#ffffff", "#1d1b20",
)
val materialDark = p(
    "#141218", "#211f26", "#2a282f", "#2b2930", "#39363f", "#e7e0e9", "#cac4d0",
    "#d0bcff", "#4d3c84", "rgba(208,188,255,0.45)", "#371e73", "#a6d388", "#e6b4ff",
    "#0f0d14", "#211f26", "#e7e0e9",
)

val sumiStyle = DirectionStyle(10.dp, 7.dp, 15.dp, 11.dp, FontFamily.Serif)
val neonStyle = DirectionStyle(16.dp, 11.dp, 22.dp, 14.dp, FontFamily.SansSerif)
val materialStyle = DirectionStyle(20.dp, 13.dp, 28.dp, 999.dp, FontFamily.SansSerif)

/** Public palette resolver (used by the non-Compose overlay service). */
fun resolvePalette(direction: ThemeDirection, mode: AppMode, accent: String): Palette =
    paletteFor(direction, mode).withAccent(accent, mode)

private fun paletteFor(direction: ThemeDirection, mode: AppMode): Palette = when (direction) {
    ThemeDirection.SUMI -> if (mode == AppMode.DARK) sumiDark else sumiLight
    ThemeDirection.NEON -> if (mode == AppMode.DARK) neonDark else neonLight
    ThemeDirection.MATERIAL -> if (mode == AppMode.DARK) materialDark else materialLight
}

private fun styleFor(direction: ThemeDirection): DirectionStyle = when (direction) {
    ThemeDirection.SUMI -> sumiStyle
    ThemeDirection.NEON -> neonStyle
    ThemeDirection.MATERIAL -> materialStyle
}

/** Apply a curated accent override (white-on-accent safe), like themeVars() in theme.jsx. */
private fun Palette.withAccent(accent: String, mode: AppMode): Palette {
    if (accent == "auto" || accent.isBlank()) return this
    val a = parseColor(accent)
    return copy(
        accent = a,
        accentSoft = a.copy(alpha = if (mode == AppMode.DARK) 0.16f else 0.12f),
        accentBorder = a.copy(alpha = 0.42f),
        onAccent = Color.White,
    )
}

@Composable
fun AppTheme(
    direction: ThemeDirection,
    mode: AppMode,
    accent: String = "auto",
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(direction, mode).withAccent(accent, mode)
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalDirStyle provides styleFor(direction),
        LocalTextScale provides textScale,
        content = content,
    )
}
