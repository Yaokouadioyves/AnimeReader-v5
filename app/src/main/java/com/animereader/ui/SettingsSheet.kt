package com.animereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
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

    val methodSub = if (settings.method == TranslationMethod.OFFLINE) "ML Kit — sur l'appareil"
        else "IA cloud — ${settings.provider.displayName}"
    val voiceSub = if (settings.voiceMethod == VoiceMethod.OFFLINE) "Moteur TTS de l'appareil"
        else "Voix IA — ${settings.voiceProvider.displayName}"

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

            // ── Traduction ──
            GroupLabel("Traduction")
            Card {
                Stacked("Méthode", methodSub) {
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
                }
                Divider()
                Inline("Traduire automatiquement", "Avant la lecture à voix haute") {
                    ARSwitch(settings.translateOn) { v -> onUpdate { it.copy(translateOn = v) } }
                }
                Divider()
                Stacked("Langue cible", null) {
                    LangGridMini(settings.lang) { code -> onUpdate { it.copy(lang = code) } }
                }
            }

            // ── Voix & lecture ──
            GroupLabel("Voix & lecture")
            Card {
                Inline("Lire à voix haute", "Désactivez pour traduire sans lecture audio") {
                    ARSwitch(settings.ttsEnabled) { v -> onUpdate { it.copy(ttsEnabled = v) } }
                }
                Divider()
                if (settings.ttsEnabled) {
                    Stacked("Synthèse vocale", voiceSub) {
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
                    }
                    Divider()
                    Inline("Vitesse de lecture", null) {
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
                    Divider()
                    Stacked("Timbre", null) {
                        Segment(options = Catalog.timbres.map { it to it }, selected = settings.timbre, compact = true) { v ->
                            onUpdate { it.copy(timbre = v) }
                        }
                    }
                    Divider()
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        AppIcon("sparkle", 15.dp, pal.accent); Spacer(Modifier.size(8.dp))
                        Text("Mode traduction seule — les bulles sont traduites et affichées, sans audio.",
                            color = pal.textMuted, fontSize = fs(12f))
                    }
                    Divider()
                }
                Stacked("Intervalle de capture", "Fréquence de relecture de la zone") {
                    Segment(options = Catalog.captureIntervals.map { "${it}s" to it }, selected = settings.captureInterval, compact = true) { v ->
                        onUpdate { it.copy(captureInterval = v) }
                    }
                }
                Divider()
                Inline("Lecture automatique", "Démarrer dès qu'un texte est détecté") {
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
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(style.radius))
            .background(pal.surface)
            .border(1.5.dp, pal.border, RoundedCornerShape(style.radius))
            .padding(horizontal = 15.dp),
        content = content,
    )
}

/** Label + optional sub on the left, a control on the right (toggles, slider). */
@Composable
private fun Inline(label: String, sub: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { LabelText(label, sub) }
        control()
    }
}

/** Label + optional sub on top, a full-width control below (segments, grids). */
@Composable
private fun Stacked(label: String, sub: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        LabelText(label, sub)
        Spacer(Modifier.size(12.dp))
        content()
    }
}

@Composable
private fun LabelText(label: String, sub: String?) {
    val pal = LocalPalette.current
    Text(label, color = pal.text, fontSize = fs(14f), fontWeight = FontWeight.SemiBold)
    if (sub != null) Text(sub, color = pal.textMuted, fontSize = fs(11.5f),
        modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border))
}

@Composable
private fun <T> Segment(options: List<Pair<String, T>>, selected: T, compact: Boolean = false, onSelect: (T) -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(pal.surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (label, value) ->
            val on = value == selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) pal.elev else androidx.compose.ui.graphics.Color.Transparent)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            val on = item == selected
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(style.radiusSm))
                    .background(if (on) pal.online.copy(alpha = 0.08f) else pal.surface2)
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
private fun LangGridMini(selected: String, onSelect: (String) -> Unit) {
    val pal = LocalPalette.current; val style = LocalDirStyle.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Catalog.languages.forEach { l ->
            val on = l.code == selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(style.radiusSm))
                    .background(if (on) pal.accentSoft else pal.surface2)
                    .border(1.5.dp, if (on) pal.accentBorder else pal.border, RoundedCornerShape(style.radiusSm))
                    .clickable { onSelect(l.code) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l.tag, color = if (on) pal.accent else pal.text, fontSize = fs(13f), fontWeight = FontWeight.Bold)
            }
        }
    }
}
