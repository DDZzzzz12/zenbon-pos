package org.example.project.features.financialdocuments.presentation.state

import org.example.project.core.presentation.error.UiError

/**
 * UI state for the document upload form.
 *
 * The upload carries the **file only** since Stage 4: bytes, filename, type and a free-text
 * description. Invoice and bank-statement metadata used to be typed here and are not any more —
 * invoice data is entered in adminApp's capture form after upload, and the server ignores the
 * invoice fields that remain on the wire for older clients.
 *
 * @since 2.0.0
 */
data class DocumentUploadUiState(
    /** Whether upload dialog is visible */
    val showUploadDialog: Boolean = false,
    /** Whether upload is in progress */
    val isUploading: Boolean = false,
    /** Error if upload failed */
    val error: UiError? = null,
    /** Success message after successful upload */
    val successMessage: String? = null,
    /** Selected file bytes */
    val selectedFileBytes: ByteArray? = null,
    /** Selected filename */
    val selectedFileName: String? = null,
    /** Selected document type (INVOICE, BANK_STATEMENT, CONTRACTS, CUSTOM) */
    val fileType: String = "INVOICE",
    /** Document description */
    val description: String = "",
    /** Raw OCR text from document scan */
    val ocrText: String? = null,
    /** Field validation errors (field name -> error message) */
    val validationErrors: Map<String, String> = emptyMap(),
    /** Upload progress percentage (0-100) */
    val uploadProgress: Int = 0,
    /** Available document types loaded from server (built-in + additional) */
    val availableDocumentTypes: List<String> = emptyList(),
    /** Whether document types are currently loading */
    val isLoadingDocumentTypes: Boolean = false,
) {
    /**
     * Checks if form has any validation errors.
     */
    fun hasErrors(): Boolean = validationErrors.isNotEmpty()

    /**
     * Checks if form is ready to submit.
     */
    fun canSubmit(): Boolean =
        selectedFileBytes != null &&
            selectedFileName != null &&
            fileType.isNotBlank() &&
            !hasErrors() &&
            !isUploading

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DocumentUploadUiState

        if (showUploadDialog != other.showUploadDialog) return false
        if (isUploading != other.isUploading) return false
        if (error != other.error) return false
        if (successMessage != other.successMessage) return false
        if (selectedFileBytes != null) {
            if (other.selectedFileBytes == null) return false
            if (!selectedFileBytes.contentEquals(other.selectedFileBytes)) return false
        } else if (other.selectedFileBytes != null) {
            return false
        }
        if (selectedFileName != other.selectedFileName) return false
        if (fileType != other.fileType) return false
        if (description != other.description) return false
        if (ocrText != other.ocrText) return false
        if (validationErrors != other.validationErrors) return false
        if (uploadProgress != other.uploadProgress) return false
        if (availableDocumentTypes != other.availableDocumentTypes) return false
        if (isLoadingDocumentTypes != other.isLoadingDocumentTypes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = showUploadDialog.hashCode()
        result = 31 * result + isUploading.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        result = 31 * result + (successMessage?.hashCode() ?: 0)
        result = 31 * result + (selectedFileBytes?.contentHashCode() ?: 0)
        result = 31 * result + (selectedFileName?.hashCode() ?: 0)
        result = 31 * result + fileType.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + (ocrText?.hashCode() ?: 0)
        result = 31 * result + validationErrors.hashCode()
        result = 31 * result + uploadProgress
        result = 31 * result + availableDocumentTypes.hashCode()
        result = 31 * result + isLoadingDocumentTypes.hashCode()
        return result
    }
}
