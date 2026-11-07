package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.error.UiError

/**
 * State of one document upload: the file the operator chose, whether it is still in flight, and the
 * id the server gave it.
 *
 * [documentId] is the only output that matters to a parent — everything else is presentation. It is
 * deliberately cleared the moment a new file starts uploading: between picking a replacement and the
 * server accepting it there is no valid id, and leaving the previous one in place would let a parent
 * submit the *old* document while the operator is looking at the new file's name.
 *
 * [fileName] survives a failed upload on purpose, so the error line can say which file failed.
 */
data class DocumentUploadState(
    val fileName: String? = null,
    val documentId: Long? = null,
    val isUploading: Boolean = false,
    /**
     * What the server made of the file, once it has answered.
     *
     * Null until then, and null for hosts that never look at it — the Kassenbuch entry form wants a
     * Beleg attached and nothing more. Kept separate from [error] because three of the four outcomes
     * are successes: an upload that found no e-invoice filed the document perfectly well.
     */
    val outcome: UploadOutcome? = null,
    /**
     * The server's answer to the last successful upload, for hosts that read more of it than
     * [outcome] maps — the bank-statement import reads its `statementImportNotices`. Null until then.
     */
    val uploaded: DocumentDto.DocumentResponseDto? = null,
    /** Non-null while the operator is deciding what to do with a password-protected PDF. */
    val unlockPrompt: UnlockPrompt? = null,
    val error: UiError? = null,
) {
    /** True once a file has been uploaded and the server has issued an id for it. */
    val hasDocument: Boolean
        get() = documentId != null
}
