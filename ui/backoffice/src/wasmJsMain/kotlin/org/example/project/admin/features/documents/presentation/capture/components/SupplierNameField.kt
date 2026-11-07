package org.example.project.admin.features.documents.presentation.capture.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.theme.PosSpacing

/**
 * Lieferant / Firma, with the creditor catalogue behind it.
 *
 * A hand-captured invoice carries no supplier id — the server resolves the creditor from the
 * invoice's own seller fields, matching VAT id, tax number, IBAN and then the *normalized* name, and
 * **creates one when nothing matches**. That has always happened, silently: an operator typing
 * "Metro Deutschland GmbH" for the creditor filed as "Metro AG" got a second creditor and was told
 * nothing, and the two then split that supplier's open total between them.
 *
 * So this field does two things the plain text field could not. It offers the creditors we already
 * hold, and picking one writes that creditor's spelling verbatim — which is what makes the link land
 * on it rather than on a near-duplicate. And it says which of the two outcomes is coming, so
 * creating a creditor is a decision the operator sees rather than a side effect they discover in
 * Lieferanten a week later.
 *
 * It stays a **text field with suggestions**, never a closed picker: a scan routinely names a
 * supplier the catalogue has never seen, and forcing a pick would stop the invoice being filed at
 * all.
 */
@Composable
internal fun SupplierNameField(
    value: String,
    onValueChange: (String) -> Unit,
    errorKey: StringKey?,
    suggestions: List<SupplierDto.ResponseDto>,
    matched: SupplierDto.ResponseDto?,
    searchAnswered: Boolean,
    onPick: (SupplierDto.ResponseDto) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CaptureTextField(
            value = value,
            onValueChange = onValueChange,
            label = requiredLabel(str(AdminStringKeys.DocumentsFieldVendor)),
            errorKey = errorKey,
            enabled = enabled,
        )

        // Only once a lookup has answered. Before that "no creditor by that name" would be a claim
        // about a search that has not run, which is a different thing from a search that found none.
        if (enabled && searchAnswered && value.isNotBlank()) {
            SupplierOutcomeLine(matched)
            // Offered only where it is the answer: with no creditor by this name, filling in its VAT
            // id and address now is better than letting the server mint a bare one and correcting it
            // in Lieferanten later.
            if (matched == null) {
                Text(
                    text = str(AdminStringKeys.InvoiceCaptureSupplierCreate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onCreate),
                )
            }
        }

        // The exact match is not offered again — it is already in the field, and picking it would be
        // a no-op the operator cannot distinguish from a broken button.
        val offerable = suggestions.filter { it.id != matched?.id }
        if (enabled && offerable.isNotEmpty()) {
            Text(
                text = str(AdminStringKeys.InvoiceCaptureSupplierSuggestions),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            offerable.forEach { supplier -> SuggestionRow(supplier = supplier, onPick = onPick) }
        }
    }
}

/** Which of the two outcomes the save will take, in one line. */
@Composable
private fun SupplierOutcomeLine(matched: SupplierDto.ResponseDto?) {
    if (matched != null) {
        Text(
            text = str(AdminStringKeys.InvoiceCaptureSupplierLinked, matched.name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        Text(
            text = str(AdminStringKeys.InvoiceCaptureSupplierUnknown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One creditor on offer: its name, and whatever second identifier it has.
 *
 * Two creditors can share a name — that is precisely the mess this field exists to prevent — so the
 * VAT id or creditor number is shown where there is one, or the city as a last resort. A row that
 * says only "Metro AG" twice is not a choice.
 */
@Composable
private fun SuggestionRow(
    supplier: SupplierDto.ResponseDto,
    onPick: (SupplierDto.ResponseDto) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onPick(supplier) }
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = PosSpacing.md, vertical = PosSpacing.sm),
    ) {
        Text(
            text = supplier.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        supplier.secondaryLabel()?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The most identifying thing this creditor has besides its name, or null when it has none. */
private fun SupplierDto.ResponseDto.secondaryLabel(): String? =
    vatId?.takeIf { it.isNotBlank() }
        ?: creditorNo?.takeIf { it.isNotBlank() }
        ?: city?.takeIf { it.isNotBlank() }
