package com.animereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animereader.data.Catalog
import com.animereader.data.Settings
import com.animereader.data.TextProvider
import com.animereader.data.TranslationMethod
import com.animereader.data.VoiceMethod
import com.animereader.data.VoiceProviderId
import com.animereader.ui.components.ARSwitch
import com.animereader.ui.components.AppIcon
import com.animereader.ui.components.DangerButton
import com.animereader.ui.components.fs
import com.animereader.ui.theme.LocalDirStyle
import com.animereader.ui.theme.LocalPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    running: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val pal = LocalPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = pal.elev) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                AppIcon("sliders", 20.dp, pal.text); Spacer(Modifier.size(9.dp))
                Text("Réglages", color = pal.text, fontSize = fs(17f), fontWeight = FontWeight.Bold)
            }

            GroupLabel("Traduction")
            Card {
                Segment(
                    options = listOf("Hors-ligne" to TranslationMethod.OFFLINE, "IA en ligne" to TranslationMethod.ONLINE),
                    selected = settings.method,
                ) { v -> onUpdate { it.copy(method = v) } }
                if (settings.method == TranslationMethod.ONLINE) {
                    Spacer(Modifier.size(10.dp))
                    ProviderRow(TextProvider.entries, settings.provider, { it.displayName }, { it.org }) { v ->
                        onUpdate { s -> s.copy(provider = v) }
                    }
                }
                SRow("Traduire automatiquement", "Avant la lecture à voix haute") {
                    ARSwitch(settings.translateOn) { v -> onUpdate { it.copy(translateOn = v) } }
                }
                LangGrid(settings.lang) { code -> onUpdate { it.copy(lang = code) } }
            }

            GroupLabel("Voix & lecture")
            Card {
                SRow("Lire à voix haute", "Désactivez pour traduire sans audio") {
                    ARSwitch(settings.ttsEnabled) { v -> onUpdate { it.copy(ttsEnabled = v) } }
                }
                if (settings.ttsEnabled) {
                    Segment(
                        options = listOf("Hors-ligne" to VoiceMethod.OFFLINE, "IA en ligne" to VoiceMethod.ONLINE),
                        selected = settings.voiceMethod,
                    ) { v -> onUpdate { it.copy(voiceMethod = v) } }
                    if (settings.voiceMethod == VoiceMethod.ONLINE) {
                        Spacer(Modifier.size(10.dp))
                        ProviderRow(VoiceProviderId.entries, settings.voiceProvider, { it.displayName }, { it.org }) { v ->
                            onUpdate { s -> s.copy(voiceProvider = v) }
                        }
                    }
                    SRow("Vitesse de lecture", null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = settings.speed, onValueChange = { v -> onUpdate { it.copy(speed = v) } },
                                valueRange = 0.5f..2f, modifier = Modifier.size(width = 120.dp, height = 24.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(String.format("%.1f×", settings.speed), color = pal.accent,
                                fontSize = fs(13f), fontWeight = FontWeight.Bold)
                        }
                    }
                    SRow("Timbre", null) {
                        Segment(
                            options = Catalog.timbres.map { it to it }, selected = settings.timbre, compact = true,
                        ) { v -> onUpdate { it.copy(timbre = v) } }
                    }
                } else {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        AppIcon("sparkle", 15.dp, pal.accent); Spacer(Modifier.size(8.dp))
                        Text("Mode traduction seule — les bulles sont traduites et affichées, sans audio.",
                            color = pal.textMuted, fontSize = fs(12f))
                    }
                }
                SRow("Intervalle de capture", "Fréquence de relecture de la zone") {
                    Segment(
                        options = Catalog.captureIntervals.map { "${it}s" to it }, selected = settings.captureInterval, compact = true,
                    ) { v -> onUpdate { it.copy(captureInterval = v) } }
                }
                SRow("Lecture automatique", "Démarrer dès qu’un texte est détecté") {
                    ARSwitch(settings.autoplay) { v -> onUpdate { it.copy(autoplay = v) } }
                }
            }

            if (running) {
                Spacer(Modifier.size(18.dp))
                DangerButton("Arrêter AnimeReader", onClick = onStop)
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), color = LocalPalette.current.textMuted, fontSize = fs(11f),
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 9.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Column(
        Modifier.fillMaxWidth()
            .background(pal.surface, RoundedCornerShape(style.radius))
            .border(1.5.dp, pal.border, RoundedCornerShape(style.radius))
            .padding(horizontal = 15.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
private fun SRow(label: String, sub: String?, control: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.text, fontSize = fs(14f), fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = pal.textMuted, fontSize = fs(11.5f))
        }
        control()
    }
}

@Composable
private fun <T> Segment(options: List<Pair<String, T>>, selected: T, compact: Boolean = false, onSelect: (T) -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(pal.surface2, RoundedCornerShape(999.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier.weight(1f)
                    .background(if (on) pal.elev else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(999.dp))
                    .clickable { onSelect(value) }
                    .padding(vertical = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) pal.text else pal.textMuted,
                    fontSize = fs(if (compact) 12f else 12.5f), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun <T> ProviderRow(
    items: List<T>, selected: T, name: (T) -> String, org: (T) -> String, onSelect: (T) -> Unit,
) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            val on = item == selected
            Column(
                Modifier.weight(1f)
                    .background(if (on) pal.online.copy(alpha = 0.08f) else pal.surface2, RoundedCornerShape(style.radiusSm))
                    .border(1.5.dp, if (on) pal.online.copy(alpha = 0.45f) else pal.border, RoundedCornerShape(style.radiusSm))
                    .clickable { onSelect(item) }
                    .padding(vertical = 11.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name(item), color = if (on) pal.online else pal.text, fontSize = fs(12f), fontWeight = FontWeight.Bold)
                Text(org(item), color = pal.textMuted, fontSize = fs(9f))
            }
        }
    }
}

@Composable
private fun LangGrid(selected: String, onSelect: (String) -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Catalog.languages.forEach { l ->
            val on = l.code == selected
            Column(
                Modifier.weight(1f)
                    .background(if (on) pal.accentSoft else pal.surface2, RoundedCornerShape(style.radiusSm))
                    .border(1.5.dp, if (on) pal.accentBorder else pal.border, RoundedCornerShape(style.radiusSm))
                    .clickable { onSelect(l.code) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(l.tag, color = if (on) pal.accent else pal.text, fontSize = fs(13f), fontWeight = FontWeight.Bold)
                Text(if (l.code == "fr") "Orig." else l.label.take(4), color = pal.textMuted, fontSize = fs(9.5f))
            }
        }
    }
}
