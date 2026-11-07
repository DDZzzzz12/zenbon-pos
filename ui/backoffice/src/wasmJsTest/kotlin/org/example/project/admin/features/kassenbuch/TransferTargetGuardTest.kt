package org.example.project.admin.features.kassenbuch

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.kassenbuch.presentation.BookManagementViewModel
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FISCAL_ID = 31L
private const val TRESOR_ID = 32L
private const val RETIRED_ID = 33L

private fun targetBook(
    id: Long,
    name: String,
    isFiscal: Boolean,
    isActive: Boolean = true,
) = CashBookDto.ResponseDto(
    id = id,
    name = name,
    isFiscal = isFiscal,
    datevAccount = "1600",
    closeInterval = CashBookCloseInterval.MANUAL,
    isActive = isActive,
    sortOrder = 0,
    currentBalance = "0.00",
    currency = "EUR",
)

/**
 * The automatic-Geldtransit picker: what it may name, and how "off" stays distinguishable from
 * "not loaded".
 *
 * The server refuses a fiscal or deactivated target with a 422 (`ConfigurationRoutes`' PUT), so the
 * client must never offer or send one — the other half of CLAUDE.md rule 8.
 */
class TransferTargetGuardTest {
    private fun books() =
        FakeCashBookApiClient(
            books =
                listOf(
                    targetBook(FISCAL_ID, "Ladenkasse", isFiscal = true),
                    targetBook(TRESOR_ID, "Tresor", isFiscal = false),
                    targetBook(RETIRED_ID, "Altkasse", isFiscal = false, isActive = false),
                ),
        )

    private fun viewModel(
        config: FakeFiscalConfigurationApiClient,
        cashBooks: FakeCashBookApiClient = books(),
    ) = BookManagementViewModel(cashBookApiClient = cashBooks, fiscalConfigurationApiClient = config)

    @Test
    fun onlyActiveNonFiscalBooksAreOffered() =
        runTest {
            val vm = viewModel(FakeFiscalConfigurationApiClient())
            vm.load()

            assertEquals(listOf(TRESOR_ID), vm.state.transferTargetCandidates.map { it.id })
        }

    @Test
    fun theFiscalBookIsNeverItsOwnTransferTarget() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)
            vm.load()

            vm.setTransferTarget(FISCAL_ID)
            assertNull(vm.state.transferTargetId, "the Ladenkasse is the source of the transfer, not a destination")

            vm.saveTransferTarget()
            assertTrue(
                config.updateRequests.none { it.cashBookId == FISCAL_ID },
                "the fiscal book must never reach the wire as a transfer target",
            )
        }

    @Test
    fun aDeactivatedBookIsNeverTheTransferTarget() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)
            vm.load()

            vm.setTransferTarget(RETIRED_ID)

            assertNull(vm.state.transferTargetId)
            assertTrue(config.updateRequests.none { it.cashBookId == RETIRED_ID })
        }

    @Test
    fun switchingTheTransferOffSendsAnExplicitNull() =
        runTest {
            // Starts ON, so turning it off is a real change with something to send. "Off" reaches
            // the wire as cashBookId = null; the request DTO has no default, so the field is always
            // present and an omitted one can never be misread as off.
            val config = FakeFiscalConfigurationApiClient(storedTarget = TRESOR_ID)
            val vm = viewModel(config)
            vm.load()
            assertEquals(TRESOR_ID, vm.state.transferTargetId)

            vm.setTransferTarget(null)
            assertTrue(vm.state.hasUnsavedTransferTarget)

            vm.saveTransferTarget()

            assertNull(config.updateRequests.single().cashBookId, "off is an explicit null, not an absent field")
            assertNull(vm.state.transferTargetId)
            assertFalse(vm.state.hasUnsavedTransferTarget)
        }

    @Test
    fun theTargetIsNeverSavedBeforeItHasBeenRead() =
        runTest {
            val config = FakeFiscalConfigurationApiClient(storedTarget = TRESOR_ID)
            val vm = viewModel(config)

            // No load(): the stored value is unknown, and the state's null is "not read yet", not
            // "off". Saving here would write an off the operator never chose.
            assertFalse(vm.state.isTransferTargetLoaded)
            vm.saveTransferTarget()

            assertTrue(config.updateRequests.isEmpty())
        }

    @Test
    fun pickingABookSendsItsId() =
        runTest {
            val config = FakeFiscalConfigurationApiClient()
            val vm = viewModel(config)
            vm.load()

            vm.setTransferTarget(TRESOR_ID)
            vm.saveTransferTarget()

            assertEquals(TRESOR_ID, config.updateRequests.single().cashBookId)
            assertEquals("Tresor", vm.state.transferTargetName)
        }
}
