package org.example.project.features.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.data.remote.TimeTrackingApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.dto.timetracking.CostCenterDto
import org.example.project.core.domain.dto.timetracking.TimeApprovalDto
import org.example.project.core.domain.dto.timetracking.TimeEntryDto
import org.example.project.core.domain.dto.timetracking.WageTypeDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient
import org.example.project.features.settings.presentation.viewmodel.DatevSettingsViewModel
import org.example.project.testutils.fakes.FakePaymentTypeApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the DATEV account-mapping slice of [DatevSettingsViewModel]:
 * load failure handling, malformed-input validation, save round-trip, and
 * preservation of mappings whose payment type is absent from the fetched list.
 *
 * Uses [runCurrent] instead of advanceUntilIdle because the VM starts an
 * infinite scheduled-job polling loop (60s virtual delay) that never idles.
 * Each test also explicitly cancels `vm.viewModelScope` as its last statement:
 * `runTest` itself drains the scheduler via `advanceUntilIdle` once the test
 * body completes (independent of what the body called), and that poller
 * shares the scheduler — left running, it hangs the test suite forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatevAccountMappingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** QA admin19 NOTE-h: a manager (no BACKUP_CREATE) got a 403 dialog from the init backup load. */
    @Test
    fun `without BACKUP_CREATE no backup endpoint is called`() =
        runTest(testDispatcher) {
            val backup = FakeDatevBackupApiClient()
            val vm = makeVm(FakeDatevAccountingConfigurationApiClient(), FakePaymentTypeApiClient(), backup, canManageBackups = false)
            runCurrent()
            vm.loadBackupConfig()
            vm.loadAvailableBackups()
            runCurrent()

            assertEquals(0, backup.backupCalls)
            assertEquals(null, vm.uiState.value.error)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `with BACKUP_CREATE the backup config loads at init`() =
        runTest(testDispatcher) {
            val backup = FakeDatevBackupApiClient()
            val vm = makeVm(FakeDatevAccountingConfigurationApiClient(), FakePaymentTypeApiClient(), backup, canManageBackups = true)
            runCurrent()

            assertTrue(backup.backupCalls > 0)
            vm.viewModelScope.cancel()
        }

    /** F63: "Backup created" was English text under the "Export erfolgreich abgeschlossen" title. */
    @Test
    fun `creating a backup raises a localized notice with its own title and the file path`() =
        runTest(testDispatcher) {
            val vm = makeVm(FakeDatevAccountingConfigurationApiClient(), FakePaymentTypeApiClient())
            runCurrent()

            vm.selectDirectory("/srv/backups")
            vm.createBackup()
            runCurrent()

            val notice = assertNotNull(vm.uiState.value.notice)
            assertEquals(DatevSettingsKeys.backupCreatedTitle, notice.title)
            assertEquals(DatevSettingsKeys.backupCreatedMessage, notice.message)
            assertEquals(listOf("/srv/backups/pos_backup.zip"), notice.args)
            vm.clearNotice()
            assertEquals(null, vm.uiState.value.notice)
            vm.viewModelScope.cancel()
        }

    /** F63: the restore toast carried the server's English message under the export title. */
    @Test
    fun `a restore raises a localized notice naming the restored file, not the server's text`() =
        runTest(testDispatcher) {
            val vm = makeVm(FakeDatevAccountingConfigurationApiClient(), FakePaymentTypeApiClient())
            runCurrent()

            vm.selectRestoreFile("/srv/backups/pos_backup.zip")
            vm.confirmRestore()
            runCurrent()

            val notice = assertNotNull(vm.uiState.value.notice)
            assertEquals(DatevSettingsKeys.restoreCompletedTitle, notice.title)
            assertEquals(DatevSettingsKeys.restoreCompletedMessage, notice.message)
            assertEquals(listOf("/srv/backups/pos_backup.zip"), notice.args)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `payment-type fetch failure surfaces error and keeps loaded false`() =
        runTest(testDispatcher) {
            val payment =
                FakePaymentTypeApiClient().apply {
                    failure = NetworkApiException(RuntimeException("offline"))
                }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()

            val s = vm.uiState.value
            assertNotNull(s.error)
            assertFalse(s.accountMappingState.loaded)
            assertFalse(s.accountMappingState.isLoading)
            assertTrue(config.updateRequests.isEmpty())
            vm.viewModelScope.cancel()
        }

    @Test
    fun `unparseable konto sets validation error key and blocks save`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()
            vm.updateAccountMappingKasseKonto("1000")
            vm.updateAccountMappingCell(true, KassenbuchLineKind.STANDARD, "19", "12a4", "")
            vm.saveDatevAccountMapping()
            runCurrent()

            assertEquals(
                DatevSettingsKeys.mappingInvalidKonto,
                vm.uiState.value.accountMappingState.validationErrorKey,
            )
            assertTrue(config.updateRequests.isEmpty())
            vm.viewModelScope.cancel()
        }

    /** F54 #6: saving the XML configuration is a save, not an export, and is not English text. */
    @Test
    fun `saving the XML configuration raises the localized saved notice, not the export toast`() =
        runTest(testDispatcher) {
            val vm = makeVm(FakeDatevAccountingConfigurationApiClient(), FakePaymentTypeApiClient())
            runCurrent()

            vm.saveDatevXmlConfig()
            runCurrent()

            assertEquals(DatevSettingsKeys.datevXmlConfigSaved, vm.uiState.value.savedMessage)
            assertEquals(null, vm.uiState.value.notice)
            vm.clearSavedMessage()
            assertEquals(null, vm.uiState.value.savedMessage)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `happy path saves mapping with cells matching state`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()
            assertTrue(vm.uiState.value.accountMappingState.loaded)

            vm.updateAccountMappingKasseKonto("1000")
            vm.updateAccountMappingCell(true, KassenbuchLineKind.STANDARD, "19", "8400", "")
            vm.updateAccountMappingPaymentCell(2L, "1360", "")
            KassenbuchSpecialBooking.entries.forEach { vm.updateAccountMappingSpecialCell(it, "9000", "") }
            vm.saveDatevAccountMapping()
            runCurrent()

            assertEquals(1, config.updateRequests.size)
            val request = config.updateRequests.single()
            assertEquals(1000, request.kasseKonto)
            assertEquals(8400, request.income[KassenbuchLineKind.STANDARD]?.get("19")?.konto)
            assertEquals(1360, request.paymentTypes[2L]?.konto)
            assertEquals(KassenbuchSpecialBooking.entries.toSet(), request.special.keys)
            assertEquals(DatevSettingsKeys.accountMappingSaved, vm.uiState.value.savedMessage, "a localized save confirmation (F54 #6)")
            assertEquals(null, vm.uiState.value.notice, "a save must not raise the export-completed toast")
            vm.viewModelScope.cancel()
        }

    @Test
    fun `mapping for payment type absent from fetch is shown and preserved on save`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config =
                FakeDatevAccountingConfigurationApiClient().apply {
                    mappingResponse =
                        DatevAccountMappingDto.MappingResponseDto(
                            shopId = 1L,
                            mapping =
                                DatevAccountMappingDto.MappingDto(
                                    kontenrahmen = Kontenrahmen.SKR03,
                                    kasseKonto = 1000,
                                    paymentTypes =
                                        mapOf(
                                            2L to DatevAccountMappingDto.AccountCell(1360),
                                            99L to DatevAccountMappingDto.AccountCell(1700),
                                        ),
                                    special = storedSpecial(),
                                ),
                        )
                }
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()

            val rows = vm.uiState.value.accountMappingState.paymentTypes
            assertEquals(listOf(2L, 99L), rows.map { it.paymentTypeId })
            assertEquals("#99", rows.first { it.paymentTypeId == 99L }.name)

            vm.saveDatevAccountMapping()
            runCurrent()

            val request = config.updateRequests.single()
            assertEquals(1360, request.paymentTypes[2L]?.konto)
            assertEquals(1700, request.paymentTypes[99L]?.konto)
            assertEquals(storedSpecial(), request.special, "stored special accounts must survive a load/save round-trip")
            vm.viewModelScope.cancel()
        }

    /**
     * T5-1: the server refuses a mapping unless every special slot is set, and the screen used to
     * send none — no shop could save a mapping, so no Buchungsstapel could be exported.
     */
    @Test
    fun `SKR03 defaults fill every special slot and the save sends them`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()
            vm.onAccountMappingKontenrahmenSelected(Kontenrahmen.SKR03)
            vm.applyAccountMappingDefaults()
            vm.saveDatevAccountMapping()
            runCurrent()

            val request = config.updateRequests.single()
            assertEquals(KassenbuchSpecialBooking.entries.toSet(), request.special.keys)
            assertEquals(1360, request.special[KassenbuchSpecialBooking.GELDTRANSIT]?.konto)
            assertTrue(request.validate().isEmpty(), "the saved mapping must pass the server's validation")
            vm.viewModelScope.cancel()
        }

    /**
     * QA finding: the server used to refuse a mapping with a bare `{"error": …}` naming only the first
     * problem, which decoded to an INTERNAL_ERROR. It now sends VALIDATION_FAILED problem+json with
     * every failing cell as a field error; the screen must carry all of them to the error dialog.
     */
    @Test
    fun `a server-side refusal surfaces every field error and ends the save`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val refusal =
                listOf(
                    FieldError("kasseKonto", "Konto must be 1..99999999"),
                    FieldError("special.CASH_SURPLUS", "no account configured"),
                )
            val config =
                FakeDatevAccountingConfigurationApiClient().apply {
                    updateFailure = PosApiException(PosErrorCode.VALIDATION_FAILED, 400, "DATEV account mapping is invalid", null, refusal)
                }
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()
            vm.onAccountMappingKontenrahmenSelected(Kontenrahmen.SKR03)
            vm.applyAccountMappingDefaults()
            vm.saveDatevAccountMapping()
            runCurrent()

            val error = assertIs<UiError.FromServer>(vm.uiState.value.error)
            assertEquals(PosErrorCode.VALIDATION_FAILED, error.code)
            assertEquals(refusal, error.fieldErrors)
            assertFalse(vm.uiState.value.accountMappingState.isSaving)
            assertFalse(vm.uiState.value.accountMappingState.configured)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `SKR04 defaults fill every special slot, and a cleared slot is named instead of sent`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()

            vm.loadDatevAccountMapping()
            runCurrent()
            vm.onAccountMappingKontenrahmenSelected(Kontenrahmen.SKR04)
            vm.applyAccountMappingDefaults()

            val special = vm.uiState.value.accountMappingState.special
            assertEquals("1460", special[KassenbuchSpecialBooking.GELDTRANSIT]?.konto)
            // F51: the employee-tip slot has a default in SKR04 too (1370 Durchlaufende Posten).
            assertEquals("1370", special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
            // F60: the private accounts are seeded from DATEV's SKR04 2026 chart.
            assertEquals("2180", special[KassenbuchSpecialBooking.PRIVATE_DEPOSIT]?.konto)
            assertEquals("2100", special[KassenbuchSpecialBooking.PRIVATE_WITHDRAWAL]?.konto)
            // The five slots mirroring SKR03, seeded 2026-10-06 (DATEV SKR04 2026, Art.-Nr. 11175).
            assertEquals("9000", special[KassenbuchSpecialBooking.CASH_DEPOSIT]?.konto)
            assertEquals("9000", special[KassenbuchSpecialBooking.CASH_WITHDRAWAL]?.konto)
            assertEquals("9000", special[KassenbuchSpecialBooking.BALANCE_CARRY_FORWARD]?.konto)
            assertEquals("4830", special[KassenbuchSpecialBooking.CASH_SURPLUS]?.konto)
            assertEquals("6300", special[KassenbuchSpecialBooking.CASH_SHORTAGE]?.konto)

            // The guard still names a slot the operator cleared, and sends nothing.
            vm.updateAccountMappingSpecialCell(KassenbuchSpecialBooking.CASH_SURPLUS, "", "")
            vm.saveDatevAccountMapping()
            runCurrent()
            assertEquals(DatevSettingsKeys.mappingSpecialMissing, vm.uiState.value.accountMappingState.validationErrorKey)
            assertTrue(config.updateRequests.isEmpty())

            vm.updateAccountMappingSpecialCell(KassenbuchSpecialBooking.CASH_SURPLUS, "4830", "")
            vm.saveDatevAccountMapping()
            runCurrent()
            val request = config.updateRequests.single()
            assertEquals(KassenbuchSpecialBooking.entries.toSet(), request.special.keys)
            assertEquals(4830, request.special[KassenbuchSpecialBooking.CASH_SURPLUS]?.konto)
            assertEquals(6300, request.special[KassenbuchSpecialBooking.CASH_SHORTAGE]?.konto)
            assertTrue(request.validate().isEmpty(), "the saved mapping must pass the server's validation")
            vm.viewModelScope.cancel()
        }

    /**
     * „Konto automatisch zuordnen": switching a cell off disables its fields (state.autoAssign drives
     * `enabled`), keeps what they hold, lets a special slot be saved empty, and is sent as
     * `autoAssign = false`. „Standardkonten übernehmen" turns every switch back on.
     */
    @Test
    fun `switching automatic assignment off keeps the fields, allows an empty slot and is sent on save`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()
            vm.loadDatevAccountMapping()
            runCurrent()
            vm.onAccountMappingKontenrahmenSelected(Kontenrahmen.SKR03)
            vm.applyAccountMappingDefaults()

            vm.setAccountMappingCellAutoAssign(true, KassenbuchLineKind.STANDARD, "7", false)
            vm.setAccountMappingPaymentAutoAssign(2L, false)
            vm.setAccountMappingSpecialAutoAssign(KassenbuchSpecialBooking.CASH_SURPLUS, false)
            vm.updateAccountMappingSpecialCell(KassenbuchSpecialBooking.CASH_SURPLUS, "", "")

            val s = vm.uiState.value.accountMappingState
            val seven = s.income.getValue(KassenbuchLineKind.STANDARD).getValue("7")
            assertEquals(false, seven.autoAssign, "the fields are disabled while the switch is off")
            assertEquals("8300", seven.konto, "the account is kept, only disabled")
            assertEquals(
                false,
                s.paymentTypes
                    .single()
                    .cell.autoAssign,
            )

            vm.saveDatevAccountMapping()
            runCurrent()
            assertEquals(null, vm.uiState.value.accountMappingState.validationErrorKey, "an empty switched-off slot is not missing")
            val request = config.updateRequests.single()
            assertEquals(
                DatevAccountMappingDto.AccountCell(konto = 8300, autoAssign = false),
                request.income[KassenbuchLineKind.STANDARD]?.get("7"),
            )
            assertEquals(DatevAccountMappingDto.AccountCell(konto = 1360, autoAssign = false), request.paymentTypes[2L])
            assertEquals(DatevAccountMappingDto.AccountCell(konto = null, autoAssign = false), request.special[KassenbuchSpecialBooking.CASH_SURPLUS])
            assertEquals(true, request.income[KassenbuchLineKind.STANDARD]?.get("19")?.autoAssign)
            assertTrue(request.validate().isEmpty(), "${request.validate()}")

            // „Standardkonten übernehmen" switches everything back on.
            vm.applyAccountMappingDefaults()
            val reset = vm.uiState.value.accountMappingState
            assertTrue(reset.special.values.all { it.autoAssign } && reset.paymentTypes.all { it.cell.autoAssign })
            assertTrue(
                reset.income.values
                    .flatMap { it.values }
                    .all { it.autoAssign },
            )
            vm.viewModelScope.cancel()
        }

    @Test
    fun `an empty ON slot is still refused, and the bulk action leaves every special booking unassigned`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config = FakeDatevAccountingConfigurationApiClient()
            val vm = makeVm(config, payment)
            runCurrent()
            vm.loadDatevAccountMapping()
            runCurrent()
            vm.applyAccountMappingDefaults()
            vm.updateAccountMappingSpecialCell(KassenbuchSpecialBooking.CASH_DEPOSIT, "", "")
            vm.saveDatevAccountMapping()
            runCurrent()
            assertEquals(DatevSettingsKeys.mappingSpecialMissing, vm.uiState.value.accountMappingState.validationErrorKey)
            assertTrue(config.updateRequests.isEmpty())

            vm.switchOffAllSpecialAutoAssign()
            vm.saveDatevAccountMapping()
            runCurrent()
            val request = config.updateRequests.single()
            assertTrue(request.special.values.all { !it.autoAssign }, "${request.special}")
            assertEquals(1360, request.special[KassenbuchSpecialBooking.GELDTRANSIT]?.konto, "the accounts are kept")
            assertEquals(null, request.special[KassenbuchSpecialBooking.CASH_DEPOSIT]?.konto)
            vm.viewModelScope.cancel()
        }

    @Test
    fun `a stored switched-off cell loads with its switch off`() =
        runTest(testDispatcher) {
            val payment = FakePaymentTypeApiClient().apply { paymentTypes = listOf(cardType()) }
            val config =
                FakeDatevAccountingConfigurationApiClient().apply {
                    mappingResponse =
                        DatevAccountMappingDto.MappingResponseDto(
                            shopId = 1L,
                            mapping =
                                DatevAccountMappingDto.MappingDto(
                                    kontenrahmen = Kontenrahmen.SKR03,
                                    kasseKonto = 1000,
                                    special =
                                        storedSpecial() +
                                            (KassenbuchSpecialBooking.CASH_SURPLUS to DatevAccountMappingDto.AccountCell(null, autoAssign = false)),
                                ),
                        )
                }
            val vm = makeVm(config, payment)
            runCurrent()
            vm.loadDatevAccountMapping()
            runCurrent()
            val cell =
                vm.uiState.value.accountMappingState.special
                    .getValue(KassenbuchSpecialBooking.CASH_SURPLUS)
            assertEquals(false, cell.autoAssign)
            assertEquals("", cell.konto)
            vm.viewModelScope.cancel()
        }

    private fun storedSpecial(): Map<KassenbuchSpecialBooking, DatevAccountMappingDto.AccountCell> =
        KassenbuchSpecialBooking.entries.associateWith { DatevAccountMappingDto.AccountCell(9000 + it.ordinal) }

    private fun cardType() =
        PaymentTypeDto.PaymentTypeResponseDto(
            id = 2L,
            name = "Karte",
            isCash = false,
            isActive = true,
            isVoucher = false,
            updatedAt = "2026-01-01T00:00:00",
        )

    private fun makeVm(
        config: FakeDatevAccountingConfigurationApiClient,
        payment: FakePaymentTypeApiClient,
        backup: FakeDatevBackupApiClient = FakeDatevBackupApiClient(),
        canManageBackups: Boolean = true,
    ) = DatevSettingsViewModel(
        backupApiClient = backup,
        tseSettingsApi = FakeDatevTseSettingsApiClient(),
        shopConfigurationApiClient = FakeDatevShopConfigurationApiClient(),
        accountingConfigurationApiClient = config,
        fiscalConfigurationApiClient = FakeDatevFiscalConfigurationApiClient(),
        scheduledJobApiClient = FakeDatevScheduledJobApiClient(),
        paymentTypeApiClient = payment,
        canManageBackups = { canManageBackups },
    )
}

// ─────────────────── Inline fakes ───────────────────
// The VM's init block loads DATEV XML config, backup config, Kassenmeldung config,
// and starts scheduled-job polling. Endpoints whose onError path sets state.error
// return benign values; endpoints whose failures are swallowed throw
// NetworkApiException; everything never called throws NotImplementedError.

private class FakeDatevAccountingConfigurationApiClient : AccountingConfigurationApiClient {
    var mappingResponse = DatevAccountMappingDto.MappingResponseDto(shopId = 1L, mapping = null)
    var getMappingFailure: Exception? = null
    val updateRequests = mutableListOf<DatevAccountMappingDto.MappingDto>()
    var updateFailure: Exception? = null

    override suspend fun getDatevAccountMapping(shopId: Long): DatevAccountMappingDto.MappingResponseDto {
        getMappingFailure?.let { throw it }
        return mappingResponse
    }

    override suspend fun updateDatevAccountMapping(
        shopId: Long,
        request: DatevAccountMappingDto.MappingDto,
    ): DatevAccountMappingDto.MappingResponseDto {
        updateRequests.add(request)
        updateFailure?.let { throw it }
        return DatevAccountMappingDto.MappingResponseDto(shopId = shopId, mapping = request)
    }

    // Called from VM init; must succeed so state.error stays null before the tests act.
    override suspend fun getDatevXmlConfig(shopId: Long) =
        ShopConfigurationDto.DatevXmlConfigResponseDto(
            shopId = shopId,
            kassenName = "Kasse",
            exportPath = "",
        )

    override suspend fun updateDatevXmlConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDatevXmlConfigRequestDto,
    ) = ShopConfigurationDto.DatevXmlConfigResponseDto(shopId = shopId, kassenName = request.kassenName, exportPath = request.exportPath)

    override suspend fun getDocumentStorageConfig(shopId: Long) = throw NotImplementedError()

    override suspend fun updateDocumentStorageConfig(
        shopId: Long,
        request: ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto,
    ) = throw NotImplementedError()
}

private class FakeDatevShopConfigurationApiClient : ShopConfigurationApiClient {
    override suspend fun getBrowserStations(shopId: Long) = throw NotImplementedError()

    override suspend fun updateBrowserStations(
        shopId: Long,
        request: ShopConfigurationDto.UpdateBrowserStationsRequestDto,
    ) = throw NotImplementedError()

    override suspend fun listDirectories(path: String?) = throw NotImplementedError()
}

private class FakeDatevBackupApiClient : BackupApiClient {
    var backupCalls = 0

    // Called from VM init; must succeed so state.error stays null.
    override suspend fun getBackupConfig(): BackupRestoreDto.BackupConfigDto {
        backupCalls++
        return BackupRestoreDto.BackupConfigDto()
    }

    // Called from VM init (after config load); failure is swallowed.
    override suspend fun listBackups(directory: String?): BackupRestoreDto.BackupListDto {
        backupCalls++
        throw NetworkApiException(RuntimeException("offline"))
    }

    override suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto?) = throw NotImplementedError()

    override suspend fun restoreBackup(
        backupData: ByteArray,
        filename: String,
    ) = throw NotImplementedError()

    override suspend fun updateBackupConfig(config: BackupRestoreDto.UpdateBackupConfigDto) = throw NotImplementedError()

    override suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto) =
        BackupRestoreDto.BackupToServerResultDto(
            success = true,
            filePath = "${request.savePath}/pos_backup.zip",
            filename = "pos_backup.zip",
            sizeBytes = 1L,
            createdAt = "2026-10-06T09:00:00",
        )

    // The server's message is English free text; the VM must not show it.
    override suspend fun restoreFromServerFile(filePath: String) =
        BackupRestoreDto.RestoreResultDto(success = true, message = "Database restored successfully", restoredAt = "2026-10-06T09:00:00")

    override suspend fun listDirectories(path: String?) = throw NotImplementedError()

    override suspend fun getRestoredPaths() = throw NotImplementedError()

    override suspend fun resolveRestoredPaths(request: RestoredPathsDto.ResolveRequestDto) = throw NotImplementedError()
}

private class FakeDatevScheduledJobApiClient : ScheduledJobApiClient {
    // Polled from VM init; failure is swallowed (best-effort polling).
    override suspend fun list() = throw NetworkApiException(RuntimeException("offline"))

    override suspend fun getDetail(id: String) = throw NotImplementedError()

    override suspend fun runNow(id: String) = throw NotImplementedError()

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = throw NotImplementedError()

    override suspend fun listExecutions(
        id: String,
        limit: Int,
        offset: Int,
    ) = throw NotImplementedError()

    override suspend fun listTypes() = throw NotImplementedError()
}

private class FakeDatevDsfinvkApiClient : DsfinvkApiClient {
    override suspend fun exportZip(request: org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.ExportRequestDto) = throw NotImplementedError()

    override suspend fun validate(
        startDate: String,
        endDate: String,
    ) = throw NotImplementedError()

    override suspend fun getStatistics(
        startDate: String,
        endDate: String,
    ) = throw NotImplementedError()
}

private class FakeDatevTseSettingsApiClient : TseSettingsApiClient {
    override suspend fun getConfig() = throw NotImplementedError()

    override suspend fun updateConfig(request: TseConfigDto.UpdateTseConfigRequest) = throw NotImplementedError()

    override suspend fun getStatus() = throw NotImplementedError()

    override suspend fun getInfo() = throw NotImplementedError()

    override suspend fun getHealth() = throw NotImplementedError()

    override suspend fun getFlashHealth() = throw NotImplementedError()

    override suspend fun ping() = throw NotImplementedError()

    override suspend fun runSelfTest() = throw NotImplementedError()

    override suspend fun updateTime() = throw NotImplementedError()

    override suspend fun setup(credentials: TseDto.SetupCredentialsRequestDto) = throw NotImplementedError()

    override suspend fun unlockAdmin(pin: String) = throw NotImplementedError()

    override suspend fun lockAdmin() = throw NotImplementedError()

    override suspend fun adminSessionStatus() = throw NotImplementedError()

    override suspend fun verifyExport(fileName: String) = throw NotImplementedError()

    override suspend fun restartTse() = throw NotImplementedError()

    override suspend fun factoryReset() = throw NotImplementedError()

    override suspend fun exportFull() = throw NotImplementedError()

    override suspend fun exportIncremental(request: TseDto.IncrementalExportRequestDto) = throw NotImplementedError()

    override suspend fun exportTimeFiltered(request: TseDto.TimeFilteredExportRequestDto) = throw NotImplementedError()

    override suspend fun getExportSize() = throw NotImplementedError()

    override suspend fun deleteExportedData() = throw NotImplementedError()

    override suspend fun registerExportArchive(fileName: String) = throw NotImplementedError()

    override suspend fun checkFirmwareUpdate() = throw NotImplementedError()

    override suspend fun applyBundledFirmwareUpdate() = throw NotImplementedError()

    override suspend fun decommission() = throw NotImplementedError()

    override suspend fun disableExportIfCspTestFails() = throw NotImplementedError()

    override suspend fun browseEntries(request: TseDto.LogEntryBrowseRequestDto) = throw NotImplementedError()

    override suspend fun exportLcm() = throw NotImplementedError()

    override suspend fun getClients() = throw NotImplementedError()

    override suspend fun getTransactions() = throw NotImplementedError()

    override suspend fun getLastTransaction() = throw NotImplementedError()
}

private class FakeDatevTimeTrackingApiClient : TimeTrackingApiClient {
    override suspend fun clockIn(request: TimeEntryDto.ClockInRequestDto) = throw NotImplementedError()

    override suspend fun clockOut(request: TimeEntryDto.ClockOutRequestDto) = throw NotImplementedError()

    override suspend fun startBreak(request: TimeEntryDto.BreakStartRequestDto) = throw NotImplementedError()

    override suspend fun endBreak(request: TimeEntryDto.BreakEndRequestDto) = throw NotImplementedError()

    override suspend fun getActiveShifts() = throw NotImplementedError()

    override suspend fun getCurrentShift() = throw NotImplementedError()

    override suspend fun getMyEntries(
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getMonthlyOverview(
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getAuthenticatedEntries(request: TimeEntryDto.AuthenticatedEntriesRequestDto) = throw NotImplementedError()

    override suspend fun createManualEntry(request: TimeEntryDto.ManualEntryRequestDto) = throw NotImplementedError()

    override suspend fun updateEntry(
        id: String,
        request: TimeEntryDto.UpdateEntryRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deleteEntry(id: String) = throw NotImplementedError()

    override suspend fun getTeamEntries(
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getAllEntries(
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getEntriesByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getMonthlyOverviewByEmployeeId(
        employeeId: String,
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun submitMonth(request: TimeApprovalDto.SubmitRequestDto) = throw NotImplementedError()

    override suspend fun approveMonth(
        id: String,
        request: TimeApprovalDto.ApproveRequestDto,
    ) = throw NotImplementedError()

    override suspend fun rejectMonth(
        id: String,
        request: TimeApprovalDto.RejectRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getPendingApprovals() = throw NotImplementedError()

    override suspend fun getApprovalStatus(
        year: Int,
        month: Int,
    ) = throw NotImplementedError()

    override suspend fun getCostCenters(includeInactive: Boolean) = throw NotImplementedError()

    override suspend fun createCostCenter(request: CostCenterDto.CreateCostCenterRequestDto) = throw NotImplementedError()

    override suspend fun updateCostCenter(
        id: String,
        request: CostCenterDto.UpdateCostCenterRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deleteCostCenter(id: String) = throw NotImplementedError()

    override suspend fun getWageTypes(includeInactive: Boolean) = throw NotImplementedError()

    override suspend fun createWageType(request: WageTypeDto.CreateWageTypeRequestDto) = throw NotImplementedError()

    override suspend fun updateWageType(
        id: String,
        request: WageTypeDto.UpdateWageTypeRequestDto,
    ) = throw NotImplementedError()

    override suspend fun deleteWageType(id: String) = throw NotImplementedError()
}

private class FakeDatevFiscalConfigurationApiClient : FiscalConfigurationApiClient {
    // Called from VM init; failure is swallowed (only isLoading is reset).
    override suspend fun getKassenMeldungConfig(shopId: Long) = throw NetworkApiException(RuntimeException("offline"))

    override suspend fun downloadKassenMeldungPdf(shopId: Long) = throw NotImplementedError()

    override suspend fun getCashBookTransferTarget(shopId: Long) = throw NotImplementedError()

    override suspend fun updateCashBookTransferTarget(
        shopId: Long,
        request: ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto,
    ) = throw NotImplementedError()

    override suspend fun getKassenbuchBelegAge(shopId: Long) = throw NotImplementedError()

    override suspend fun updateKassenbuchBelegAge(
        shopId: Long,
        request: ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto,
    ) = throw NotImplementedError()
}
