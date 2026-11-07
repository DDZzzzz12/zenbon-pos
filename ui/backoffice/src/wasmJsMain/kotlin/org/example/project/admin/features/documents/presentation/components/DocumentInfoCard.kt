package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentFacts
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.PosCard

/**
 * Read-only base-data card for document types with no editable metadata through this API —
 * [org.example.project.core.domain.enums.document.DocumentType.CONTRACTS], `EIGENBELEG` and
 * `ZBON_REPORT`. The client only exposes `updateBankStatementMetadata`; the
 * server's generic metadata PUT writes into an opaque column no response DTO reads back, and
 * `description` has no field on [DocumentDto.DocumentResponseDto] at all. Building an editor bound to
 * either would silently write into a hole, so this renders only fields the DTO genuinely carries.
 */
@Composable
fun DocumentInfoCard(
    document: DocumentDto.DocumentResponseDto?,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.DocumentSectionBase),
                style = MaterialTheme.typography.titleMedium,
            )
            if (document != null) {
                InfoRow(str(AdminStringKeys.DocumentsColFile), document.filename)
                InfoRow(str(AdminStringKeys.DocumentsColType), documentTypeLabel(document.fileType, language))
                InfoRow(str(AdminStringKeys.DocumentsColDate), document.uploadedAt.formatShortDateTime(language))
                InfoRow(str(AdminStringKeys.DocumentsFieldUploadedBy), document.uploadedBy)
                InfoRow(
                    str(AdminStringKeys.DocumentsFieldProcessingStatus),
                    str(processingStatusLabelKey(document.processingStatus, document.fileType)),
                )
                InfoRow(str(AdminStringKeys.DocumentsColLinked), DocumentFacts.realLinkCount(document).toString())
                InfoRow(str(AdminStringKeys.DocumentsFieldFileSize), document.formattedFileSize)
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Text(text = label, style = MaterialTheme.typography.labelMedium)
    Text(text = value, style = MaterialTheme.typography.bodyMedium)
}
