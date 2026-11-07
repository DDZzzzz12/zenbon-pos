package org.example.project.features.changelog.presentation.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.data.remote.ChangelogApiClient
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.changelog.ChangelogDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.OverlayEventBusImpl
import org.example.project.core.presentation.error.ClientErrorReporter
import org.example.project.core.presentation.error.NoOpClientErrorReporter
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.testutils.fakes.FakeTokenStore
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Every release entry of the "What's new" panel names its version.
 *
 * Release titles describe what a release brings, not which one it is, so an entry without its
 * version cannot be matched to the version the update screen or a till reports. The card has two
 * layouts — the newest entry on a wide window puts its metadata in a side column, every other entry
 * stacks it above the title — and the expanded case below renders both at once.
 *
 * `ChangelogScreen` lives in shared-ui, which has no Compose UI-test harness; composeApp's desktop
 * tests do, and it consumes the same composable.
 */
@OptIn(ExperimentalTestApi::class)
class ChangelogScreenVersionTest {
    @BeforeTest
    fun setUp() {
        TranslationRegistry.initialize(defaultTranslations())
        // The screen's PosErrorWatcher injects these even when there is no error to show.
        startKoin {
            modules(
                module {
                    single { SessionState(FakeTokenStore.create(), StartupPolicy.WIPE) }
                    single<ClientErrorReporter> { NoOpClientErrorReporter }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun `both card layouts show the version on a wide window`() =
        runComposeUiTest {
            showChangelog(Language.GERMAN, WindowSizeClass(widthDp = 1200f, heightDp = 900f))

            // The newest entry takes the wide side-column layout, the older one the stacked layout.
            onNodeWithText("Version 1.1.1").assertIsDisplayed()
            onNodeWithText("Version 1.1.0").assertIsDisplayed()
        }

    @Test
    fun `the stacked layout shows the localized version on a phone`() =
        runComposeUiTest {
            showChangelog(Language.CHINESE, WindowSizeClass(widthDp = 400f, heightDp = 900f))

            onNodeWithText("版本 1.1.1").assertIsDisplayed()
            onNodeWithText("版本 1.1.0").assertIsDisplayed()
        }

    private fun ComposeUiTest.showChangelog(
        language: Language,
        window: WindowSizeClass,
    ) {
        val viewModel = ChangelogViewModel(FakeChangelogApiClient)
        setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalWindowSizeClass provides window,
                LocalGlobalOverlayViewModel provides GlobalOverlayViewModel(OverlayEventBusImpl()),
            ) {
                ChangelogScreen(viewModel)
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.items
                .isNotEmpty()
        }
        waitForIdle()
    }

    private object FakeChangelogApiClient : ChangelogApiClient {
        override suspend fun list(
            page: Int,
            size: Int,
        ): ChangelogDto.PaginatedChangelogResponseDto =
            ChangelogDto.PaginatedChangelogResponseDto(
                items = listOf(release("1.1.1", "2026-10-10"), release("1.1.0", "2026-10-09")),
                pagination =
                    ApiResponseDto.PaginationInfo(
                        currentPage = 0,
                        pageSize = size,
                        totalItems = 2L,
                        totalPages = 1,
                        hasNext = false,
                        hasPrevious = false,
                    ),
            )

        private fun release(
            version: String,
            date: String,
        ) = ChangelogDto.ResponseDto(
            releaseVersion = version,
            releaseDate = "${date}T00:00:00Z",
            appliedAt = "${date}T00:00:00Z",
            titleEn = "Release title",
            titleDe = "Release-Titel",
            titleZh = "版本标题",
            summaryEn = "Summary",
            summaryDe = "Zusammenfassung",
            summaryZh = "摘要",
            highlightsEn = emptyList(),
            highlightsDe = emptyList(),
            highlightsZh = emptyList(),
            knownIssuesEn = emptyList(),
            knownIssuesDe = emptyList(),
            knownIssuesZh = emptyList(),
        )
    }
}
