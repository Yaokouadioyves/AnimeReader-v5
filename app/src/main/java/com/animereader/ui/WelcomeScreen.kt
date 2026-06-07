package com.animereader.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animereader.R
import com.animereader.data.AppMode
import com.animereader.data.Settings
import com.animereader.ui.components.AppIcon
import com.animereader.ui.components.CircleIconButton
import com.animereader.ui.components.DangerButton
import com.animereader.ui.components.GhostButton
import com.animereader.ui.components.PrimaryButton
import com.animereader.ui.components.fs
import com.animereader.ui.theme.LocalDirStyle
import com.animereader.ui.theme.LocalPalette

@Composable
fun WelcomeScreen(
    settings: Settings,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onTutorial: () -> Unit,
) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current

    Box(Modifier.fillMaxSize()) {
        // Top actions
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircleIconButton("sliders", onOpenSettings)
            CircleIconButton(if (settings.mode == AppMode.DARK) "sun" else "moon", onToggleMode)
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Brand mark — high-res 3D crossed-flags on the brand pink (extracted logo)
            Image(
                painter = painterResource(R.drawable.logo_fg),
                contentDescription = "AnimeReader",
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.height(26.dp))
            Text("Lisez vos mangas\nles yeux fermés", color = pal.text, fontSize = fs(30f),
                fontWeight = FontWeight.Bold, fontFamily = style.displayFamily, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Posez le cadre sur une bulle. AnimeReader la reconnaît, la traduit et la lit à voix haute.",
                color = pal.textMuted, fontSize = fs(15f), textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 290.dp))
            Spacer(Modifier.height(24.dp))

            if (running) {
                Row(
                    Modifier.background(pal.offline.copy(alpha = 0.10f), CircleShape)
                        .border(1.5.dp, pal.offline.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(pal.offline, CircleShape))
                    Spacer(Modifier.size(8.dp))
                    Text("Service actif — bulle flottante en cours", color = pal.offline,
                        fontSize = fs(12.5f), fontWeight = FontWeight.SemiBold)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("target", "OCR instantané"); Pill("headphones", "Lecture TTS")
                    }
                    Pill("sparkle", "Traduction IA", highlight = true)
                }
            }

            Spacer(Modifier.height(34.dp))

            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                if (running) {
                    PrimaryButton("Revenir à la bulle", "target", onClick = onStart)
                    DangerButton("Arrêter AnimeReader", onClick = onStop)
                } else {
                    PrimaryButton("Démarrer", "play", onClick = onStart)
                    GhostButton("Revoir le tutoriel", "refresh", onClick = onTutorial)
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: String, label: String, highlight: Boolean = false) {
    val pal = LocalPalette.current
    val color = if (highlight) pal.online else pal.textMuted
    Row(
        Modifier.background(if (highlight) pal.online.copy(alpha = 0.08f) else pal.surface2, CircleShape)
            .border(1.5.dp, if (highlight) pal.online.copy(alpha = 0.35f) else pal.border, CircleShape)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, 15.dp, color)
        Spacer(Modifier.size(6.dp))
        Text(label, color = color, fontSize = fs(12.5f), fontWeight = FontWeight.SemiBold)
    }
}
