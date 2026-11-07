package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An operator who empties the prefilled count field means "did not count". The empty field must
 * stay empty however many lookups land afterwards, and must close the day without a count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonClosingCountClearedViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var zBonApi: FakeZBonApiClient
    private lateinit var kassenbuchApi: FakeKassenbuchApiClient
    private lateinit var vm: ZBonViewModel

    private val mia =
        ZBonDto.OpenPeriodTipDto(
            employeeId = "11111111-1111-1111-1111-111111111111",
            employeeNumber = "W1",
            employeeName = "Mia",
            tipTotal = "3.0000",
            cashTip = "3.0000",
            cardTip = "0.0000",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
        zBonApi = FakeZBonApiClient()
        zBonApi.openPeriodTipsResult = listOf(mia)
        kassenbuchApi = FakeKassenbuchApiClient()
        kassenbuchApi.getCashCountExpectationResult =
            KassenbuchDto.CashCountExpectationDto(bookBalance = "20.00", unbookedCashTakings = "0.00", expectedCash = "20.00")
        vm = ZBonViewModel(zBonApi, kassenbuchApi, LanguageManager(), FakeCashBookApiClient())
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun draft(): ZBonClosingDraft = assertNotNull(vm.zBonState.value.closingDraft)

    @Test
    fun `an emptied count stays empty after every lookup and later edit`() =
        runTest(testDispatcher) {
            vm.openClosingDialog(includeTipPayouts = true)
            advanceUntilIdle()
            assertEquals("", draft().countText, "the count field starts empty (FO-33)")
            assertEquals("17,00", draft().countPlaceholder, "the drawer after Mia's 3.00 payout is only the placeholder")
            assertNull(draft().toRequest().countedAmount, "an untouched field is not sent")

            vm.updateClosingCount("")
            assertEquals("", draft().countText)
            assertNull(draft().countedAmount)
            assertTrue(draft().canConfirm)

            // Anything that re-derives the draft must leave the emptied field alone.
            vm.setClosingTipChecked(mia.employeeId, false)
            vm.setClosingTipChecked(mia.employeeId, true)
            vm.updateClosingTipAmount(mia.employeeId, "1,00")
            vm.updateClosingTopUpAmount("")
            assertEquals("", draft().countText)
            assertNull(draft().countedAmount)
            assertNull(draft().toRequest().countedAmount)
        }

    @Test
    fun `clearing the count before the lookups land is not undone when they arrive`() =
        runTest(testDispatcher) {
            vm.openClosingDialog(includeTipPayouts = true)
            vm.updateClosingCount("")
            advanceUntilIdle()
            assertEquals("", draft().countText)
            assertNull(draft().toRequest().countedAmount)
        }
}
