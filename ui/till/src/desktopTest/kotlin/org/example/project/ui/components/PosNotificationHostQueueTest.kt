package org.example.project.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.NotificationSeverity
import org.example.project.core.presentation.components.notification.NotificationState
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The overlay queue shows one notification at a time through a single `PosNotificationHost` call
 * site. Without a key per notification, the toast behind a closed one inherited its remembered
 * `visible = false` and was never drawn — while still heading the queue and blocking the rest.
 */
@OptIn(ExperimentalTestApi::class)
class PosNotificationHostQueueTest {
    @BeforeTest
    fun setUp() {
        // PosToast labels its close button via str(CommonKeys.close), which needs the registry.
        TranslationRegistry.initialize(defaultTranslations())
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun toast(
        title: String,
        id: String = Uuid.random().toString(),
    ) = NotificationState(
        id = id,
        title = title,
        message = "$title message",
        type = NotificationSeverity.ERROR,
        displayMode = DisplayMode.TOAST,
    )

    @Test
    fun theToastBehindAClosedOneIsShown() =
        runComposeUiTest {
            var queue by mutableStateOf(listOf(toast("First"), toast("Second")))
            setContent {
                PosNotificationHost(
                    notification = queue.firstOrNull(),
                    onDismiss = { queue = queue.drop(1) },
                )
            }
            onNodeWithText("First").assertIsDisplayed()

            onNodeWithContentDescription(getString(CommonKeys.close, Language.DEFAULT)).performClick()
            waitForIdle()

            onNodeWithText("Second").assertIsDisplayed()
        }

    @Test
    fun theSecondToastIsShown_evenWhenItReusesTheFirstsId() =
        runComposeUiTest {
            // A producer that reuses an id (e.g. repeated connection-error toasts with a different
            // message each time) can leave two adjacent queue entries sharing one id. Keying the
            // host on id alone would still carry the first's dismissed/finished state into the
            // second, because Compose treats same-id calls as the same call site.
            var queue by
                mutableStateOf(
                    listOf(
                        toast("First", id = "connection-error"),
                        toast("Second", id = "connection-error"),
                    ),
                )
            setContent {
                PosNotificationHost(
                    notification = queue.firstOrNull(),
                    onDismiss = { queue = queue.drop(1) },
                )
            }
            onNodeWithText("First").assertIsDisplayed()

            onNodeWithContentDescription(getString(CommonKeys.close, Language.DEFAULT)).performClick()
            waitForIdle()

            onNodeWithText("Second").assertIsDisplayed()
        }
}
