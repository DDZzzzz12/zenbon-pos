package org.example.project.features.financialdocuments.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.utils.LanguageManager
import org.example.project.features.financialdocuments.presentation.state.DocumentUploadUiState
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEvent
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEventBus

class DocumentUploadViewModel(
    private val documentApiClient: DocumentManagementApiClient,
    private val languageManager: LanguageManager,
    private val eventBus: FinancialDocumentsEventBus,
) : ViewModel() {
    private val logger = Logger.withTag("DocumentUploadViewModel")

    private val _uiState = MutableStateFlow(DocumentUploadUiState())
    val uiState: StateFlow<DocumentUploadUiState> = _uiState.asStateFlow()

    init {
        loadDocumentTypes()
        viewModelScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is FinancialDocumentsEvent.ShowUploadDialog -> {
                        _uiState.update { it.copy(showUploadDialog = true) }
                    }
                    else -> {}
                }
            }
        }
    }

    // ========== Upload Methods ==========

    fun loadDocumentTypes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDocumentTypes = true) }
            runCatchingApi { documentApiClient.getDocumentTypes() }
                .onSuccess { types ->
                    _uiState.update {
                        it.copy(availableDocumentTypes = types, isLoadingDocumentTypes = false)
                    }
                    logger.i { "DocumentUploadViewModel.loadDocumentTypes: loaded ${types.size} document types" }
                }.onError { uiError ->
                    val defaults = listOf("INVOICE", "BANK_STATEMENT", "CONTRACTS", "CUSTOM")
                    _uiState.update {
                        it.copy(availableDocumentTypes = defaults, isLoadingDocumentTypes = false)
                    }
                    logger.w { "DocumentUploadViewModel.loadDocumentTypes: failed, using defaults - $uiError" }
                }
        }
    }

    fun selectFile(
        bytes: ByteArray,
        fileName: String,
    ) {
        _uiState.update {
            it.copy(
                selectedFileBytes = bytes,
                selectedFileName = fileName,
                validationErrors = emptyMap(),
            )
        }
        logger.i { "DocumentUploadViewModel.selectFile: file selected $fileName (${bytes.size} bytes)" }
    }

    /**
     * The upload carries a file and nothing that needs validating beyond its presence.
     *
     * The amount / IBAN / date rules that used to live here went with the metadata fields in Stage 4.
     * Their equivalents now sit in adminApp's capture form, where they can be checked against what
     * the DATEV export actually accepts rather than against a regex.
     */
    private fun validateUploadFields() {
        val state = _uiState.value
        val errors = mutableMapOf<String, String>()

        if (state.selectedFileBytes == null || state.selectedFileName == null) {
            val language = languageManager.currentLanguage.value
            errors["file"] = getString(DocumentManagementKeys.requiredFieldError, language)
        }

        _uiState.update { it.copy(validationErrors = errors) }
    }

    fun submitUpload() {
        validateUploadFields()

        if (_uiState.value.hasErrors()) {
            logger.w { "DocumentUploadViewModel.submitUpload: upload form has validation errors" }
            return
        }

        val state = _uiState.value

        if (state.selectedFileBytes == null || state.selectedFileName == null) {
            logger.e { "DocumentUploadViewModel.submitUpload: cannot upload, no file selected" }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, error = null, uploadProgress = 0) }

            _uiState.update { it.copy(uploadProgress = 25) }

            // The file, its type and a description — nothing else. Invoice and bank-statement
            // metadata are no longer typed at upload time: an e-invoice payload is parsed by the
            // server, and anything else is entered in adminApp afterwards.
            val result =
                runCatchingApi {
                    documentApiClient.uploadDocument(
                        fileBytes = state.selectedFileBytes,
                        fileName = state.selectedFileName,
                        fileType = state.fileType,
                        description = state.description.takeIf { it.isNotBlank() },
                        ocrText = state.ocrText,
                    )
                }

            result
                .onSuccess { uploaded ->
                    val language = languageManager.currentLanguage.value
                    delay(1500)
                    clearUploadForm()
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            uploadProgress = 100,
                            successMessage = getString(DocumentManagementKeys.uploadSuccessMessage, language),
                        )
                    }
                    logger.i { "DocumentUploadViewModel.submitUpload: successfully uploaded ${uploaded.filename}" }
                    viewModelScope.launch {
                        eventBus.emit(FinancialDocumentsEvent.UploadCompleted(fileType = state.fileType, documentId = uploaded.id))
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            uploadProgress = 0,
                            error = uiError,
                        )
                    }
                    logger.e { "DocumentUploadViewModel.submitUpload: upload failed - $uiError" }
                }
        }
    }

    fun clearUploadForm() {
        _uiState.update { DocumentUploadUiState() }
        // Restore document types
        loadDocumentTypes()
        logger.i { "DocumentUploadViewModel.clearUploadForm: upload form cleared" }
    }

    /**
     * Hide upload dialog.
     */
    fun hideUploadDialog() {
        _uiState.update { it.copy(showUploadDialog = false) }
    }

    // ========== Field Setters ==========

    fun updateFileType(fileType: String) {
        _uiState.update { it.copy(fileType = fileType) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }
}
