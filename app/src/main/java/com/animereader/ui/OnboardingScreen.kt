package com.animereader.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animereader.ui.components.AppIcon
import com.animereader.ui.components.PrimaryButton
import com.animereader.ui.components.fs
import com.animereader.ui.theme.LocalDirStyle
import com.animereader.ui.theme.LocalPalette

private data class Step(val icon: String, val kicker: String, val title: String, val desc: String)

private val steps = listOf(
    Step("play", "Capture d’écran", "Lisez avec vos oreilles",
        "AnimeReader observe l’écran et lit à voix haute les bulles de vos scans — par-dessus n’importe quelle app de lecture."),
    Step("target", "Zone de lecture", "Posez le cadre sur la bulle",
        "Déplacez et redimensionnez le rectangle pour épouser une bulle. Tout ce qu’il contient est reconnu, puis lu."),
    Step("sparkle", "Traduction", "Hors-ligne ou IA, au choix",
        "ML Kit traduit sur l’appareil — rapide et gratuit. Le mode IA (GPT-4o, DeepL…) ajoute du contexte en ligne."),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    var i by remember { mutableIntStateOf(0) }
    val step = steps[i]
    val last = i == steps.lastIndex

    Column(
        Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Passer", color = pal.textMuted, fontSize = fs(13f), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.End).padding(top = 18.dp, end = 4.dp).clickable { onFinish() })

        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(220.dp)
                    .background(pal.surface2, RoundedCornerShape(style.radiusLg))
                    .border(1.5.dp, pal.border, RoundedCornerShape(style.radiusLg)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(72.dp).background(pal.accent, CircleShape), contentAlignment = Alignment.Center) {
                    AppIcon(step.icon, 34.dp, pal.onAccent)
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(step.kicker.uppercase(), color = pal.accent, fontSize = fs(11f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(step.title, color = pal.text, fontSize = fs(27f), fontWeight = FontWeight.Bold,
                fontFamily = style.displayFamily, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(step.desc, color = pal.textMuted, fontSize = fs(14.5f), textAlign = TextAlign.Center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.indices.forEach { n ->
                    Box(
                        Modifier
                            .height(8.dp)
                            .width(if (n == i) 26.dp else 8.dp)
                            .background(if (n == i) pal.accent else pal.border, CircleShape)
                            .clickable { i = n },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (last) "Commencer" else "Suivant") {
                if (last) onFinish() else i++
            }
        }
    }
}
