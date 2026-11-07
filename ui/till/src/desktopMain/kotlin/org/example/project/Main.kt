package org.example.project

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import org.example.project.core.auth.SettingManager
import org.example.project.core.utils.LanguageProvider
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.di.initKoin
import org.example.project.di.launchLocalSecretsMigration
import org.example.project.features.main.presentation.screens.MainScreenStrings
import org.koin.java.KoinJavaComponent.get
import java.awt.Dimension
import java.awt.Font
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.UIManager

private val logger = Logger.withTag("DesktopApp")

/**
 * Configures Swing/AWT defaults for native dialogs (JFileChooser).
 *
 * The desktop FileSaver (save/download) uses a JFileChooser,
 * which picks a small default window size. This function installs an
 * AWT event listener that resizes JFileChooser dialog windows when they
 * appear, and scales all Swing fonts to match the Compose UI density.
 *
 * @param scaleFactor Multiplier from the UI density setting (e.g., 2.0 = 200%)
 */
private fun applySwingDialogScaling(scaleFactor: Float) {
    // Scale all Swing fonts so labels/buttons inside the dialog are readable
    val defaults = UIManager.getDefaults()
    for (key in defaults.keys()) {
        val value = defaults[key]
        if (value is Font) {
            UIManager.put(key, value.deriveFont(value.size2D * scaleFactor))
        }
    }
}

/** Target size for JFileChooser dialogs, updated when UI density changes. */
private var fileChooserDialogSize = Dimension(1200, 1000)
private var fileChooserListenerInstalled = false

/**
 * Installs a one-time AWT window listener that resizes JFileChooser dialogs
 * to [fileChooserDialogSize] when they open, sets them always-on-top so they
 * appear above the fullscreen Compose window, and ensures proper cleanup
 * when they close (disposes orphaned owner frames and restores focus).
 */
private fun installFileChooserResizer() {
    if (fileChooserListenerInstalled) return
    fileChooserListenerInstalled = true

    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
        val window = event.source
        if (window !is java.awt.Window) return@addAWTEventListener

        val isFileChooserDialog =
            (window as? javax.swing.JDialog)
                ?.contentPane
                ?.components
                ?.any { it is JFileChooser } == true

        if (!isFileChooserDialog) return@addAWTEventListener

        when (event.id) {
            java.awt.event.WindowEvent.WINDOW_OPENED -> {
                window.size = fileChooserDialogSize
                window.setLocationRelativeTo(null)
                // Ensure dialog appears above the fullscreen Compose window
                window.isAlwaysOnTop = true
            }
            java.awt.event.WindowEvent.WINDOW_CLOSED -> {
                // JFileChooser.showOpenDialog(null) creates an orphaned owner Frame
                // that can linger and prevent focus from returning to Compose.
                val owner = window.owner
                if (owner != null && owner !is javax.swing.JFrame) {
                    owner.dispose()
                }
                // Return focus to the Compose application window
                java.awt.Window
                    .getWindows()
                    .firstOrNull { it.isShowing && it is javax.swing.JFrame }
                    ?.toFront()
            }
        }
    }, java.awt.AWTEvent.WINDOW_EVENT_MASK)
}

/**
 * Desktop application entry point.
 */
fun main() {
    try {
        // Seals any secret an older build left in plaintext and wipes it from the database file, in the background.
        initKoin().koin.launchLocalSecretsMigration()

        // Install touch support (AWT interceptor + scroll dampening) BEFORE
        // the Compose application starts, so all events are handled from the start.
        val settingManagerRef: SettingManager = get(SettingManager::class.java)
        installTouchSupport(
            initialScrollFactor = settingManagerRef.scrollSensitivity.value.toDouble(),
            initialFlingFactor = settingManagerRef.scrollSensitivity.value.toDouble() * 0.5,
        )

        // Scale Swing/AWT dialogs to match the Compose UI density setting.
        // This makes native dialogs (the FileSaver's JFileChooser) larger
        // and more readable on HiDPI screens or when the user has scaled the UI.
        val initialDensity = settingManagerRef.uiDensity.value
        fileChooserDialogSize =
            Dimension(
                (1200 * initialDensity).toInt(),
                (1000 * initialDensity).toInt(),
            )
        installFileChooserResizer()
        if (initialDensity > 1.0f) {
            applySwingDialogScaling(initialDensity)
        }

        application {
            // Reactively update scroll dampening when settings change
            val scrollSensitivity by settingManagerRef.scrollSensitivity.collectAsState()

            LaunchedEffect(scrollSensitivity) {
                updateScrollFactor(scrollSensitivity.toDouble())
                updateFlingFactor(scrollSensitivity.toDouble() * 0.5)
            }

            // Reactively update Swing dialog scaling when UI density changes
            val uiDensity by settingManagerRef.uiDensity.collectAsState()
            LaunchedEffect(uiDensity) {
                fileChooserDialogSize =
                    Dimension(
                        1200,
                        1000,
                    )
                applySwingDialogScaling(uiDensity)
            }

            var showExitDialog by remember { mutableStateOf(false) }

            val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen)

            val appIcon =
                remember {
                    val stream =
                        Thread
                            .currentThread()
                            .contextClassLoader
                            .getResourceAsStream("icon.png")
                    stream?.let {
                        BitmapPainter(ImageIO.read(it).toComposeImageBitmap())
                    }
                }

            Window(
                onCloseRequest = { showExitDialog = true },
                title = BuildKonfig.APP_NAME,
                state = windowState,
                icon = appIcon,
            ) {
                ProvideWindowSizeClass {
                    LanguageProvider {
                        val language = LocalLanguageManager.current.currentLanguage.value

//                        WithTouchFriendlyConfig {
                        WithScaling {
                            if (showExitDialog) {
                                AlertDialog(
                                    onDismissRequest = { showExitDialog = false },
                                    title = { Text(MainScreenStrings.getExitDialogTitle(language)) },
                                    text = { Text(MainScreenStrings.getExitDialogMessage(language)) },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                exitApplication()
                                            },
                                        ) {
                                            Text(MainScreenStrings.getExitConfirmButton(language))
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = { showExitDialog = false },
                                        ) {
                                            Text(MainScreenStrings.getExitConfirmButton(language))
                                        }
                                    },
                                )
                            }
                            App()
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.e(e) { "Failed to start desktop application" }
        throw e
    }
}
