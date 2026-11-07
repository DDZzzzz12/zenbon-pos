package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.DocumentManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDate
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.PosSpacing

/** The `creditDebit` value that means money left the account. */
private const val DEBIT = "DEBIT"

/**
 * A parsed statement's entries, shown where a PDF preview would otherwise be.
 *
 * For a bank statement the parsed rows *are* the document as far as an operator is concerned — the
 * source file is a CAMT XML or an MT940 text blob, which the PDF viewer was never built to render.
 * The original file stays downloadable from the header bar.
 *
 * A row the ingest marked as repeating an earlier import is labelled rather than hidden: the
 * duplicate flag is a hint, and the operator decides what it means.
 */
@Composable
fun StatementTransactionTable(
    transactions: List<DocumentDto.BankStatementTransactionDto>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // On a phone the five columns have no room: each entry becomes a card and the header row,
        // whose labels the card spells out inline, is dropped.
        val compact = maxWidth < PaneBreakpoints.PHONE_MAX
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = str(DocumentManagementKeys.statementTransactionsTitle),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (compact) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(transactions) { entry -> StatementTransactionCard(entry) }
                }
            } else {
                StatementHeaderRow()
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(transactions) { index, entry ->
                        if (index > 0) HorizontalDivider()
                        StatementTransactionRow(entry)
                    }
                }
            }
        }
    }
}

/**
 * One entry as a card, for a phone: counterparty and signed amount on top, both dates under them,
 * then the purpose and the already-imported flag. Carries every field the wide row does.
 */
@Composable
private fun StatementTransactionCard(entry: DocumentDto.BankStatementTransactionDto) {
    val language = LocalAppLanguage.current
    val bodyStyle = MaterialTheme.typography.bodySmall
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = entry.counterpartyName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                PosMoneyText(amount = entry.signedAmount(), currencyCode = entry.currency, style = bodyStyle)
            }
            Text(
                text =
                    "${str(DocumentManagementKeys.statementBookingDate)}: ${entry.bookingDate.orEmpty().formatShortDate(language)}" +
                        "  ·  ${str(DocumentManagementKeys.statementValueDate)}: ${entry.valueDate.orEmpty().formatShortDate(language)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.remittanceUnstructured ?: entry.remittanceStructuredRef.orEmpty(),
                style = bodyStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.duplicateOfTransactionId != null) {
                Text(
                    text = str(DocumentManagementKeys.statementAlreadyImported),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatementHeaderRow() {
    val labelStyle = MaterialTheme.typography.labelMedium
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(str(DocumentManagementKeys.statementBookingDate), Modifier.weight(1f), style = labelStyle)
        Text(str(DocumentManagementKeys.statementValueDate), Modifier.weight(1f), style = labelStyle)
        Text(str(DocumentManagementKeys.statementCounterparty), Modifier.weight(1.5f), style = labelStyle)
        Text(str(DocumentManagementKeys.statementPurpose), Modifier.weight(2f), style = labelStyle)
        Text(
            text = str(DocumentManagementKeys.statementAmount),
            modifier = Modifier.weight(1f),
            style = labelStyle,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun StatementTransactionRow(entry: DocumentDto.BankStatementTransactionDto) {
    val bodyStyle = MaterialTheme.typography.bodySmall
    val language = LocalAppLanguage.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PosSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(entry.bookingDate.orEmpty().formatShortDate(language), Modifier.weight(1f), style = bodyStyle)
        Text(entry.valueDate.orEmpty().formatShortDate(language), Modifier.weight(1f), style = bodyStyle)
        Text(
            text = entry.counterpartyName.orEmpty(),
            modifier = Modifier.weight(1.5f),
            style = bodyStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(2f)) {
            Text(
                text = entry.remittanceUnstructured ?: entry.remittanceStructuredRef.orEmpty(),
                style = bodyStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.duplicateOfTransactionId != null) {
                Text(
                    text = str(DocumentManagementKeys.statementAlreadyImported),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        PosMoneyText(
            amount = entry.signedAmount(),
            currencyCode = entry.currency,
            modifier = Modifier.weight(1f),
            style = bodyStyle,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The wire amount is always positive; the direction lives in `creditDebit`, so a debit is signed
 * here by prefixing the string.
 *
 * Deliberately string arithmetic rather than a numeric round trip — money never passes through a
 * `Double` in this codebase, and negating a parsed value would be exactly that. A blank amount is
 * left blank so the formatter renders its own unparseable placeholder instead of a bare "-".
 */
private fun DocumentDto.BankStatementTransactionDto.signedAmount(): String {
    val raw = amount.orEmpty()
    if (raw.isBlank() || creditDebit != DEBIT || raw.startsWith("-")) return raw
    return "-$raw"
}
