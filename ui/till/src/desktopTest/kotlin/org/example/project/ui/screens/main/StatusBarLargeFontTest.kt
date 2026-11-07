package org.example.project.ui.screens.main

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.notifications.presentation.state.ConnectionState
import org.example.project.features.notifications.presentation.state.TseConnectionState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** At a large font the status bar's date must stay whole: the left group gives way, not the clock. */
@OptIn(ExperimentalTestApi::class, ExperimentalTime::class)
class StatusBarLargeFontTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    @Test
    fun `the date stays inside the bar at a large font scale on a phone width`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.ENGLISH,
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.width(220.dp)) {
                        StatusBar(
                            employeeNumber = "K003",
                            terminalId = null,
                            connectionState = ConnectionState.Connected,
                            tseConnectionState = TseConnectionState.Connected,
                            language = Language.ENGLISH,
                            compact = true,
                        )
                    }
                }
            }
            waitForIdle()
            val today = StatusBarFormat.stamp(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()), compact = true)
            val bar = onRoot().getBoundsInRoot()
            val date = onNode(hasText(today)).getBoundsInRoot()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            onNode(hasText(today))
                .fetchSemanticsNode()
                .config
                .getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val layout = layouts.single()
            assertTrue(
                layout.lineCount == 1 && layout.size.width <= layout.multiParagraph.maxIntrinsicWidth.toInt() + 1,
                "date wrapped or was cut: lines=${layout.lineCount} size=${layout.size} bar=$bar date=$date",
            )
            assertTrue(date.right <= bar.right, "date ($date) must end inside the bar ($bar)")
        }
}
