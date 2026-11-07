package org.example.project.admin.features.documents.presentation.capture.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureField
import org.example.project.admin.features.documents.presentation.capture.InvoiceCaptureForm
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard

/**
 * Kreditorenkonto, Gegenkonto and Buchungstext — the three fields whoever books the invoice fills in,
 * possibly days later.
 *
 * Leaving all three blank is a clean "not yet booked" state, not an incomplete one: the server
 * refuses to create a booking row unless a field is set. These stay **editable even for an
 * `EINVOICE_XML` invoice** — booking is our decision, not the supplier's assertion, and it lives in a
 * separate table that cannot damage supplier content. So [enabled] here is `canManage`, never the
 * provenance flag.
 *
 * The two account ranges are DATEV schema constraints (`p11`, `p10039`); a value outside them makes
 * the generated XML invalid, which the server does not currently check.
 */
@Composable
internal fun InvoiceBookingSection(
    form: InvoiceCaptureForm,
    errors: Map<InvoiceCaptureField, StringKey>,
    enabled: Boolean,
    onChange: ((InvoiceCaptureForm) -> InvoiceCaptureForm) -> Unit,
    modifier: Modifier = Modifier,
    /** Scroll/focus target of each validated field, so a refused save can reveal the first error. */
    anchor: (InvoiceCaptureField) -> Modifier = { Modifier },
) {
    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(str(AdminStringKeys.InvoiceCaptureSectionBooking), style = MaterialTheme.typography.titleMedium)
            CaptureTextField(
                value = form.bpAccountNo,
                onValueChange = { v -> onChange { it.copy(bpAccountNo = v) } },
                label = str(AdminStringKeys.InvoiceCaptureFieldBpAccountNo),
                errorKey = errors[InvoiceCaptureField.BP_ACCOUNT_NO],
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.BP_ACCOUNT_NO),
            )
            CaptureTextField(
                value = form.accountNo,
                onValueChange = { v -> onChange { it.copy(accountNo = v) } },
                label = str(AdminStringKeys.InvoiceCaptureFieldAccountNo),
                errorKey = errors[InvoiceCaptureField.ACCOUNT_NO],
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.ACCOUNT_NO),
            )
            CaptureTextField(
                value = form.bookingText,
                onValueChange = { v -> onChange { it.copy(bookingText = v) } },
                label = str(AdminStringKeys.InvoiceCaptureFieldBookingText),
                errorKey = errors[InvoiceCaptureField.BOOKING_TEXT],
                enabled = enabled,
                modifier = anchor(InvoiceCaptureField.BOOKING_TEXT),
            )
        }
    }
}
