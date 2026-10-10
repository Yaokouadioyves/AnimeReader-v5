package com.animereader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animereader.BuildConfig
import com.animereader.ui.components.GhostButton
import com.animereader.ui.components.PrimaryButton
import com.animereader.ui.components.fs
import com.animereader.ui.theme.LocalPalette
import com.animereader.update.Release
import java.io.File

/** Where the in-app update stands. */
sealed interface UpdateUi {
    val release: Release
    data class Available(override val release: Release) : UpdateUi
    data class Downloading(override val release: Release, val percent: Int) : UpdateUi
    /** Downloaded, but Android must first allow AnimeReader to install apps. */
    data class NeedPermission(override val release: Release, val apk: File) : UpdateUi
    data class Failed(override val release: Release, val reason: String) : UpdateUi
}

@Composable
fun UpdateDialog(
    state: UpdateUi,
    onInstall: () -> Unit,
    onAllow: () -> Unit,
    onLater: () -> Unit,
) {
    val pal = LocalPalette.current
    val notes = state.release.notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(4).toList()
    AlertDialog(
        onDismissRequest = { if (state !is UpdateUi.Downloading) onLater() },
        containerColor = pal.elev,
        title = {
            Text("Mise à jour disponible", color = pal.text, fontSize = fs(17f), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Version 5.${state.release.versionCode} (installée : ${BuildConfig.VERSION_NAME})",
                    color = pal.textMuted, fontSize = fs(12.5f),
                )
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.size(10.dp))
                    notes.forEach { Text(it, color = pal.text, fontSize = fs(13f)) }
                }
                Spacer(Modifier.size(12.dp))
                when (state) {
                    is UpdateUi.Available -> Text(
                        "Elle s'installe par-dessus l'application actuelle, vos réglages sont gardés.",
                        color = pal.textMuted, fontSize = fs(12f),
                    )
                    is UpdateUi.Downloading -> Text(
                        if (state.percent >= 0) "Téléchargement… ${state.percent} %" else "Téléchargement…",
                        color = pal.accent, fontSize = fs(13f), fontWeight = FontWeight.Bold,
                    )
                    is UpdateUi.NeedPermission -> Text(
                        "Android doit d'abord autoriser AnimeReader à installer ses mises à jour : " +
                            "touchez « Autoriser », activez l'option, revenez puis touchez « Installer ».",
                        color = pal.text, fontSize = fs(12.5f),
                    )
                    is UpdateUi.Failed -> Text(
                        "Échec : ${state.reason}. Réessayez plus tard.",
                        color = pal.textMuted, fontSize = fs(12.5f),
                    )
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateUi.Downloading -> Unit
                    is UpdateUi.NeedPermission -> {
                        PrimaryButton("Autoriser", modifier = Modifier.fillMaxWidth(), onClick = onAllow)
                        GhostButton("Installer", modifier = Modifier.fillMaxWidth(), onClick = onInstall)
                    }
                    else -> PrimaryButton("Installer", modifier = Modifier.fillMaxWidth(), onClick = onInstall)
                }
                if (state !is UpdateUi.Downloading) {
                    GhostButton("Plus tard", modifier = Modifier.fillMaxWidth(), onClick = onLater)
                }
            }
        },
    )
}
