package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchViewModel
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FO-68: "Keine Buchungen in diesem Zeitraum" is an answer, so it may only be shown once the period
 * has actually been fetched. The state used to start with `isLoading = false` and an empty list, and
 * the first frame claimed an empty period.
 */
class KassenbuchLoadStateTest {
    private fun viewModel(entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto> = emptyList()) =
        KassenbuchViewModel(
            kassenbuchApiClient =
                FakeKassenbuchApiClient(
                    entries = { entries },
                    balance = { "0.00" },
                    detail = { error("not used") },
                ),
            cashBookApiClient = FakeCashBookApiClient(books = emptyList()),
            zBonApiClient = FakeZBonApiClient(latestEndTime = null),
            fiscalConfigurationApiClient = FakeFiscalConfigurationApiClient(),
        )

    @Test
    fun nothingHasLoadedBeforeTheFirstRequest() {
        assertFalse(viewModel().state.hasLoaded, "the empty-period notice must not show before the first load")
    }

    @Test
    fun aCompletedLoadOfAnEmptyPeriodHasLoaded() =
        runTest {
            val vm = viewModel()

            vm.load(vm.state.granularity, vm.state.anchor)

            assertTrue(vm.state.hasLoaded)
            assertTrue(vm.state.entries.isEmpty())
        }
}
