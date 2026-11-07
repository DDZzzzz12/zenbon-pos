package org.example.project.admin.features.settings

import org.example.project.admin.features.issuereport.presentation.IssueReportSettingsState
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageState
import org.example.project.admin.features.settings.presentation.sections.ShopInfoFormFields
import org.example.project.admin.features.settings.presentation.sections.ShopInfoSectionState
import org.example.project.admin.features.settings.presentation.sections.VatMappingState
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VatRules
import org.example.project.features.settings.presentation.state.BackupRestoreState
import org.example.project.features.settings.presentation.state.DatevAccountMappingCellState
import org.example.project.features.settings.presentation.state.DatevAccountMappingPaymentRow
import org.example.project.features.settings.presentation.state.DatevAccountMappingState
import org.example.project.features.settings.presentation.state.DatevXmlConfigState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure dirty comparisons behind the leave-confirmation for adminApp's settings sections. */
class SettingsLeaveDirtyStateTest {
    // ---- Geschäftsdaten ----

    @Test
    fun shopInfo_untouchedIsClean() {
        val loaded = ShopInfoFormFields(shopName = "Zenbon", shopCity = "Berlin")
        assertFalse(ShopInfoSectionState(form = loaded, saved = loaded).isDirty)
    }

    @Test
    fun shopInfo_editIsDirty_andUndoIsClean() {
        val loaded = ShopInfoFormFields(shopName = "Zenbon")
        val state = ShopInfoSectionState(form = loaded, saved = loaded)
        val edited = state.copy(form = loaded.copy(shopName = "Zenbon 2"))
        assertTrue(edited.isDirty)
        assertFalse(edited.copy(form = loaded).isDirty)
    }

    @Test
    fun shopInfo_transientFlagsDoNotMakeItDirty() {
        assertFalse(ShopInfoSectionState(isLoading = true, isSaving = true, isUploadingLogo = true).isDirty)
    }

    // ---- Belegablage ----

    private val storage =
        DocumentStorageState(
            path = "/data/docs",
            maxUploadSizeInput = "20",
            additionalDocumentTypes = listOf("Vertrag"),
            savedPath = "/data/docs",
            savedMaxUploadSizeInput = "20",
            savedAdditionalDocumentTypes = listOf("Vertrag"),
        )

    @Test
    fun documentStorage_untouchedIsClean() {
        assertFalse(storage.isDirty)
        assertFalse(storage.copy(isSaving = true, isLoading = true, newDocumentType = "").isDirty)
    }

    @Test
    fun documentStorage_eachSavedFieldMakesItDirty() {
        assertTrue(storage.copy(path = "/other").isDirty)
        assertTrue(storage.copy(maxUploadSizeInput = "30").isDirty)
        assertTrue(storage.copy(additionalDocumentTypes = listOf("Vertrag", "Angebot")).isDirty)
    }

    @Test
    fun documentStorage_undoIsClean() {
        val edited = storage.copy(maxUploadSizeInput = "30")
        assertFalse(edited.copy(maxUploadSizeInput = "20").isDirty)
    }

    // ---- MwSt-Zuordnung editor ----

    @Test
    fun vatMapping_closedEditorIsClean() {
        assertFalse(VatMappingState(mapping = VatMappingConfig.getDefault(), draft = null).isDraftDirty)
    }

    @Test
    fun vatMapping_draftEqualToStoredMappingIsClean() {
        val stored = VatMappingConfig.getDefault()
        assertFalse(VatMappingState(mapping = stored, draft = stored).isDraftDirty)
    }

    @Test
    fun vatMapping_draftWithoutStoredMappingComparesWithDefaults() {
        val defaults = VatMappingConfig.getDefault()
        assertFalse(VatMappingState(mapping = null, draft = defaults).isDraftDirty)
        val edited = defaults.copy(DRINKS = VatRules(default = "B"))
        assertTrue(VatMappingState(mapping = null, draft = edited).isDraftDirty)
    }

    // ---- DATEV XML config ----

    private val xmlConfig =
        ShopConfigurationDto.DatevXmlConfigResponseDto(shopId = 1L, kassenName = "Kasse 1", exportPath = "/export")

    @Test
    fun datevXml_unloadedIsClean() {
        assertFalse(DatevXmlConfigState(kassenName = "typed before load").isDirty)
    }

    @Test
    fun datevXml_loadedIsCleanThenEditIsDirty_andUndoIsClean() {
        val loaded = DatevXmlConfigState().withConfig(xmlConfig)
        assertFalse(loaded.isDirty)
        val edited = loaded.copy(consultantNumber = "12345")
        assertTrue(edited.isDirty)
        assertFalse(edited.copy(consultantNumber = loaded.consultantNumber).isDirty)
    }

    @Test
    fun datevXml_discardRestoresTheSavedValues() {
        val loaded = DatevXmlConfigState().withConfig(xmlConfig)
        val discarded = loaded.copy(kassenName = "changed", exportPath = "/elsewhere").withEditsDiscarded()
        assertEquals("Kasse 1", discarded.kassenName)
        assertEquals("/export", discarded.exportPath)
        assertFalse(discarded.isDirty)
    }

    @Test
    fun datevXml_transientFlagsDoNotMakeItDirty() {
        val loaded = DatevXmlConfigState().withConfig(xmlConfig)
        assertFalse(loaded.copy(isSaving = true, isLoading = true, isExpanded = true, showDirectoryBrowser = true).isDirty)
    }

    // ---- Backup config ----

    private val backupConfig = BackupRestoreDto.BackupConfigDto(defaultBackupPath = "/backups", retentionCount = 7)

    @Test
    fun backup_unloadedIsClean() {
        assertFalse(BackupRestoreState(defaultBackupPath = "typed").isConfigDirty)
    }

    @Test
    fun backup_editIsDirty_undoIsClean_andNullPathMatchesEmpty() {
        val loaded = BackupRestoreState().withConfig(backupConfig)
        assertFalse(loaded.isConfigDirty)
        val edited = loaded.copy(retentionCount = 14)
        assertTrue(edited.isConfigDirty)
        assertFalse(edited.copy(retentionCount = 7).isConfigDirty)
        assertFalse(BackupRestoreState().withConfig(BackupRestoreDto.BackupConfigDto(defaultBackupPath = null)).isConfigDirty)
    }

    @Test
    fun backup_discardRestoresTheSavedConfig_andActionsAreNotEdits() {
        val loaded = BackupRestoreState().withConfig(backupConfig)
        val busy = loaded.copy(isCreatingBackup = true, isRestoring = true, selectedRestoreFilePath = "/x.zip")
        assertFalse(busy.isConfigDirty)
        val discarded = loaded.copy(defaultBackupPath = "/else", autoEnabled = true).withConfigDiscarded()
        assertEquals("/backups", discarded.defaultBackupPath)
        assertFalse(discarded.autoEnabled)
        assertFalse(discarded.isConfigDirty)
    }

    // ---- DATEV account mapping ----

    private val paymentRows = listOf(DatevAccountMappingPaymentRow(paymentTypeId = 5L, name = "Karte", isVoucher = false))

    private fun loadedMapping() = DatevAccountMappingState(kasseKonto = "1000", paymentTypes = paymentRows, loaded = true).pinned()

    @Test
    fun accountMapping_unloadedIsClean() {
        assertFalse(DatevAccountMappingState(kasseKonto = "typed").isDirty)
    }

    @Test
    fun accountMapping_pinnedIsCleanThenEditIsDirty_andUndoIsClean() {
        val loaded = loadedMapping()
        assertFalse(loaded.isDirty)
        val edited = loaded.copy(kasseKonto = "1010")
        assertTrue(edited.isDirty)
        assertFalse(edited.copy(kasseKonto = "1000").isDirty)
        assertTrue(loaded.copy(kontenrahmen = Kontenrahmen.SKR04).isDirty)
    }

    @Test
    fun accountMapping_paymentCellEditIsDirty() {
        val loaded = loadedMapping()
        val edited =
            loaded.copy(
                paymentTypes = paymentRows.map { it.copy(cell = DatevAccountMappingCellState(konto = "1360")) },
            )
        assertTrue(edited.isDirty)
    }

    @Test
    fun accountMapping_discardRestoresSavedFields_andKeepsLoadedFlags() {
        val loaded = loadedMapping()
        val discarded = loaded.copy(kasseKonto = "9999", showSeedConfirm = true).withEditsDiscarded()
        assertEquals("1000", discarded.kasseKonto)
        assertFalse(discarded.showSeedConfirm)
        assertTrue(discarded.loaded)
        assertFalse(discarded.isDirty)
    }

    @Test
    fun accountMapping_transientFlagsDoNotMakeItDirty() {
        assertFalse(loadedMapping().copy(isSaving = true, isLoading = true, showSeedConfirm = true).isDirty)
    }

    // ---- Problem reports ----

    private val reportSettings =
        IssueReportDto.SettingsDto(
            owner = "acme",
            repo = "pos",
            apiBaseUrl = "https://api.example.invalid",
            isPublic = false,
            tokenState = IssueReportDto.TokenState.NONE,
            tokenPreview = null,
            tokenSetAt = null,
            useUpdateToken = false,
            updateTokenState = IssueReportDto.TokenState.NONE,
        )

    private fun loadedReportSettings() =
        IssueReportSettingsState(
            settings = reportSettings,
            owner = "acme",
            repo = "pos",
            apiBaseUrl = "https://api.example.invalid",
        )

    @Test
    fun issueReportSettings_untouchedIsClean() {
        assertFalse(loadedReportSettings().hasUnsavedEdits)
        assertFalse(IssueReportSettingsState().hasUnsavedEdits)
    }

    @Test
    fun issueReportSettings_targetEditOrTokenDraftIsDirty() {
        val loaded = loadedReportSettings()
        assertTrue(loaded.copy(repo = "other").hasUnsavedEdits)
        assertTrue(loaded.copy(tokenDraft = "ghp_secret").hasUnsavedEdits)
    }

    @Test
    fun issueReportSettings_blankTokenDraftAndUndoAreClean() {
        val loaded = loadedReportSettings()
        assertFalse(loaded.copy(tokenDraft = "   ").hasUnsavedEdits)
        assertFalse(loaded.copy(repo = "other").copy(repo = "pos").hasUnsavedEdits)
    }
}
