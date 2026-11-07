package org.example.project.admin.features.dataexport

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.example.project.admin.features.dataexport.presentation.DatevOutputViewModel
import org.example.project.admin.features.salesinvoices.FakeSalesInvoiceApiClient
import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.UnassignedSummary
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.network.FileDownload
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatevOutputViewModelTest {
    private fun vm(
        exportApi: FakeDatevExportApiClient = FakeDatevExportApiClient(),
        logApi: FakeDatevExportLogApiClient = FakeDatevExportLogApiClient(),
        salesApi: FakeSalesInvoiceApiClient = FakeSalesInvoiceApiClient(),
    ) = DatevOutputViewModel(exportApi, logApi, salesApi)

    private fun boom() = NetworkApiException(IllegalStateException("boom"))

    @Test
    fun load_asksTheLogForTheSelectedRangeAsIsoDateTimes() =
        runTest {
            val logApi = FakeDatevExportLogApiClient(log = listOf(exportLogEntry()))
            val viewModel = vm(logApi = logApi)

            viewModel.setRange(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31))

            assertEquals("2026-07-01T00:00", logApi.lastPeriodStart)
            // Half-open: the day after the inclusive end.
            assertEquals("2026-08-01T00:00", logApi.lastPeriodEnd)
            assertEquals(1, viewModel.state.log.size)
            assertNull(viewModel.state.error)
        }

    @Test
    fun failingLogLoad_setsATypedErrorAndStopsTheSpinner() =
        runTest {
            val logApi = FakeDatevExportLogApiClient(failWith = boom())
            val viewModel = vm(logApi = logApi)

            viewModel.load()

            assertNotNull(viewModel.state.error)
            assertEquals(false, viewModel.state.isLoading)
        }

    @Test
    fun startExport_opensTheDialogSeededWithTheCurrentRange() =
        runTest {
            val viewModel = vm()
            viewModel.setRange(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31))

            viewModel.startExport("AP_LEDGER")

            val dialog = assertNotNull(viewModel.state.adHocDialog)
            assertEquals("AP_LEDGER", dialog.type)
            assertEquals(LocalDate(2026, 7, 1), dialog.startDate)
            assertEquals(LocalDate(2026, 7, 31), dialog.endDate)
            assertTrue(dialog.isValid)
        }

    /**
     * E2E round 4, R4-4: the server now sends the export's notices with the file; they must reach
     * the screen, and a new export must not show the previous one's.
     */
    @Test
    fun exportWarnings_areShownAndClearedByTheNextExport() =
        runTest {
            val exportApi =
                FakeDatevExportApiClient(
                    download =
                        FileDownload(
                            bytes = ByteArray(3),
                            fileName = "EXTF_Buchungsstapel.csv",
                            warnings = listOf("2 booking(s) carry a Beleg date outside the exported period"),
                        ),
                )
            val viewModel = vm(exportApi = exportApi)
            viewModel.startExport("BUCHUNGSSTAPEL_MIT_BELEGEN")

            viewModel.confirmAdHocExport()

            assertEquals(
                listOf("2 booking(s) carry a Beleg date outside the exported period"),
                viewModel.state.exportWarnings,
            )

            viewModel.startExport("KASSENBUCH")
            assertTrue(viewModel.state.exportWarnings.isEmpty(), "a new export starts without the old notices")
        }

    /** Lines exported without a contra account (mapping switch off) reach the screen per export type. */
    @Test
    fun unassignedContra_isShownPerTypeAndClearedByTheNextExport() =
        runTest {
            val summary =
                UnassignedSummary.of(
                    listOf(CellRef(special = KassenbuchSpecialBooking.CASH_SURPLUS)),
                )
            val exportApi =
                FakeDatevExportApiClient(
                    download = FileDownload(bytes = ByteArray(3), fileName = "EXTF_Buchungsstapel.csv", unassignedContra = summary),
                )
            val viewModel = vm(exportApi = exportApi)
            viewModel.startExport("BUCHUNGSSTAPEL")

            viewModel.confirmAdHocExport()

            assertEquals(listOf("BUCHUNGSSTAPEL" to summary!!), viewModel.state.unassignedContra)
            viewModel.startExport("KASSENBUCH")
            assertTrue(viewModel.state.unassignedContra.isEmpty())
        }

    @Test
    fun all_fansOutToTheThreeScheduledExportTypesInOrder() =
        runTest {
            val exportApi = FakeDatevExportApiClient()
            val viewModel = vm(exportApi = exportApi)
            viewModel.setRange(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31))
            viewModel.startExport("ALL")

            viewModel.confirmAdHocExport()

            assertEquals(
                listOf("KASSENBUCH", "AP_LEDGER", "BUCHUNGSSTAPEL"),
                exportApi.adHocCalls.map { it.first },
            )
            assertNull(viewModel.state.adHocDialog)
        }

    @Test
    fun failingExport_stopsTheFanOutAndKeepsTheError() =
        runTest {
            val exportApi = FakeDatevExportApiClient(failWith = boom())
            val viewModel = vm(exportApi = exportApi)
            viewModel.startExport("ALL")

            viewModel.confirmAdHocExport()

            assertEquals(1, exportApi.adHocCalls.size)
            assertNotNull(viewModel.state.error)
            assertNull(viewModel.state.adHocDialog)
        }

    @Test
    fun invertedRange_isRefusedWithoutCallingTheEndpoint() =
        runTest {
            val exportApi = FakeDatevExportApiClient()
            val viewModel = vm(exportApi = exportApi)
            viewModel.startExport("KASSENBUCH")
            viewModel.updateAdHocEnd(LocalDate(2020, 1, 1))

            viewModel.confirmAdHocExport()

            assertTrue(exportApi.adHocCalls.isEmpty())
            assertNotNull(viewModel.state.adHocDialog)
        }

    @Test
    fun downloadFromLog_asksTheLogEndpointForThatRow() =
        runTest {
            val logApi = FakeDatevExportLogApiClient(downloadBytes = ByteArray(5))
            val viewModel = vm(logApi = logApi)

            viewModel.downloadFromLog(7L, "datev.zip")

            assertEquals(listOf(7L), logApi.downloadedIds)
            assertNull(viewModel.state.error)
        }
}
