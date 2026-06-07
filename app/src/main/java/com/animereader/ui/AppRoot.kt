package com.animereader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.animereader.data.AppMode
import com.animereader.data.Settings
import com.animereader.data.SettingsRepository
import com.animereader.service.OverlayController
import com.animereader.ui.theme.AppTheme
import com.animereader.ui.theme.LocalPalette
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

    fun update(t: (Settings) -> Settings) { scope.launch { repo.update(t) } }

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
                    )
                }
            }
        }
    }
}
