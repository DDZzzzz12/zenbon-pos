package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosSecondaryButton

/**
 * Shown on a Beleg whose invoice was cancelled by a correction (T9-19): when, why, and a way to the
 * correction itself. The Beleg and the original invoice are unchanged — this says what neutralised
 * them. Who cancelled is in the Prüfprotokoll (`CANCEL_INCOMING_INVOICE`).
 */
@Composable
fun InvoiceStornoNotice(
    storno: DocumentDto.InvoiceStornoDto,
    onOpenInvoice: (Long) -> Unit,
) {
    val language = LocalAppLanguage.current
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Text(
                str(AdminStringKeys.DocumentStornoTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                str(
                    AdminStringKeys.DocumentStornoBody,
                    storno.cancelledAt.formatShortDate(language),
                    storno.correctionInvoiceId.toString(),
                    storno.reason.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            PosSecondaryButton(
                text = str(AdminStringKeys.DocumentStornoOpenCorrection),
                onClick = { onOpenInvoice(storno.correctionInvoiceId) },
            )
        }
    }
}
