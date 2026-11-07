package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.reports.presentation.components.dailyreport.TransactionList
import org.example.project.features.reports.presentation.state.DashboardViewMode
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Berichte list keeps its scroll anchor on the row it showed first, so Belege prepended by a
 * reload after a bill change land above the visible area (emu12). A scroll-to-top request brings
 * them into view; new rows without a request (paging, pull-to-refresh) leave the scroll alone.
 */
@OptIn(ExperimentalTestApi::class)
class TransactionListScrollToTopTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun row(id: Long) =
        BillDto.BillSummaryDto(
            billId = id,
            billNumber = "RE-$id",
            billAmount = "10.00",
            paidAmount = "10.00",
            tips = "0.00",
            endTime = "2026-09-30T12:00:00",
            employeeNumber = "07",
        )

    private val before = (30L downTo 1L).map(::row)
    private val after = listOf(row(32), row(31)) + before

    private fun check(
        bumpRequest: Boolean,
        expectTopVisible: Boolean,
    ) = runComposeUiTest {
        var bills by mutableStateOf(before)
        var request by mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                Box(Modifier.height(600.dp)) {
                    TransactionList(
                        viewMode = DashboardViewMode.BILLS,
                        bills = bills,
                        tableCards = emptyList(),
                        expandedTableSessions = emptyMap(),
                        loadingTableSessions = emptySet(),
                        onToggleTableExpand = {},
                        employeeGroups = emptyList(),
                        isCompact = true,
                        isLoading = false,
                        isLoadingMore = false,
                        hasMore = false,
                        onLoadMore = {},
                        onBillClick = {},
                        scrollToTopRequest = request,
                    )
                }
            }
        }
        onNodeWithText("#RE-30", substring = true).assertExists()

        // The reloaded first page lands with the two new Belege on top — and, for a bill change, the request.
        bills = after
        if (bumpRequest) request++
        waitForIdle()

        if (expectTopVisible) {
            onNodeWithText("#RE-32", substring = true).assertExists()
        } else {
            onNodeWithText("#RE-32", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `a bill-change reload scrolls the prepended Belege into view`() = check(bumpRequest = true, expectTopVisible = true)

    @Test
    fun `new rows without a request keep the list where it was`() = check(bumpRequest = false, expectTopVisible = false)
}
