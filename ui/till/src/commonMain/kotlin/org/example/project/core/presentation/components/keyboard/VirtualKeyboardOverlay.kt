package org.example.project.core.presentation.components.keyboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardType
import org.example.project.core.utils.releaseInstantTapMode
import org.example.project.core.utils.requestInstantTapMode

@Composable
fun VirtualKeyboardOverlay(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
) {
    val controller = LocalVirtualKeyboard.current

    if (controller.isVisible) {
        // Enable instant tap mode while keyboard is visible (bypasses touch pending delay)
        // and reset height when keyboard hides
        DisposableEffect(Unit) {
            requestInstantTapMode()
            onDispose {
                releaseInstantTapMode()
                controller.keyboardHeightPx = 0
            }
        }

        Popup(
            alignment = Alignment.BottomCenter,
            properties =
                PopupProperties(
                    focusable = false,
                ),
        ) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = modifier.fillMaxWidth(),
            ) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                controller.keyboardHeightPx = size.height
                            },
                ) {
                    when (controller.keyboardType) {
                        VirtualKeyboardType.ALPHABETIC ->
                            AlphabeticKeyboardLayout(
                                onKeyPress = controller::onKeyPress,
                                onBackspace = controller::onBackspace,
                                onEnter = controller::onEnter,
                                onDismiss = controller::dismiss,
                                windowSizeClass = windowSizeClass,
                            )
                        VirtualKeyboardType.NUMERIC -> {
                            Numpad(
                                onKeyPress = controller::onKeyPress,
                                onBackspace = controller::onBackspace,
                                onEnter = controller::onEnter,
                                onHide = controller::dismiss,
                                modifier =
                                    Modifier
                                        .widthIn(max = 360.dp)
                                        .height(260.dp)
                                        .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
