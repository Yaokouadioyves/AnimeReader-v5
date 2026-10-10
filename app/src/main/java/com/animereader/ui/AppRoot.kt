package com.animereader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.animereader.BuildConfig
import com.animereader.data.AppMode
import com.animereader.data.Settings
import com.animereader.data.SettingsRepository
import com.animereader.service.OverlayController
import com.animereader.ui.theme.AppTheme
import com.animereader.ui.theme.LocalPalette
import com.animereader.update.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    repo: SettingsRepository,
    onStartReader: () -> Unit,
    onStopReader: () -> Unit,
) {
    val settings by repo.settings.collectAsState(initial = Settings())
    val running by OverlayController.running.collectAsState()
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    var updateUi by remember { mutableStateOf<UpdateUi?>(null) }

    fun update(t: (Settings) -> Settings) { scope.launch { repo.update(t) } }

    // Each merge into main is published as a release: offer it as soon as the app opens.
    LaunchedEffect(Unit) { updater.check()?.let { updateUi = UpdateUi.Available(it) } }

    fun checkNow() = scope.launch {
        val r = updater.check()
        if (r != null) updateUi = UpdateUi.Available(r)
        else Toast.makeText(context, "AnimeReader est à jour (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
    }

    fun installUpdate() {
        val cur = updateUi ?: return
        if (cur is UpdateUi.NeedPermission) {
            if (updater.canInstall()) { updater.install(cur.apk); updateUi = null }
            return
        }
        scope.launch {
            updateUi = UpdateUi.Downloading(cur.release, 0)
            val apk = runCatching {
                updater.download(cur.release) { pct -> updateUi = UpdateUi.Downloading(cur.release, pct) }
            }.getOrElse { e ->
                updateUi = UpdateUi.Failed(cur.release, e.message ?: "erreur réseau")
                return@launch
            }
            if (updater.canInstall()) { updater.install(apk); updateUi = null }
            else updateUi = UpdateUi.NeedPermission(cur.release, apk)
        }
    }

    AppTheme(settings.direction, settings.mode, settings.accent, settings.textScale) {
        Surface(Modifier.fillMaxSize(), color = LocalPalette.current.bg) {
            Box(Modifier.fillMaxSize()) {
                if (!settings.onboardingDone) {
                    OnboardingScreen(onFinish = { update { it.copy(onboardingDone = true) } })
                } else {
                    WelcomeScreen(
                        settings = settings,
                        running = running,
                        onStart = onStartReader,
                        onStop = onStopReader,
                        onToggleMode = {
                            update { it.copy(mode = if (it.mode == AppMode.DARK) AppMode.LIGHT else AppMode.DARK) }
                        },
                        onOpenSettings = { settingsOpen = true },
                        onTutorial = { update { it.copy(onboardingDone = false) } },
                    )
                }

                if (settingsOpen) {
                    SettingsSheet(
                        settings = settings,
                        running = running,
                        onUpdate = { t -> update(t) },
                        onStop = { settingsOpen = false; onStopReader() },
                        onClose = { settingsOpen = false },
                        onCheckUpdate = { checkNow() },
                    )
                }

                updateUi?.let { state ->
                    UpdateDialog(
                        state = state,
                        onInstall = { installUpdate() },
                        onAllow = { updater.openInstallPermission() },
                        onLater = { updateUi = null },
                    )
                }
            }
        }
    }
}
