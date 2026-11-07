package org.example.project.features.financialdocuments.presentation.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.money.Decimal
import org.example.project.money.sum
import kotlin.time.Clock

/**
 * Compose-observable state holder for the "Create Kassenbuch entry" form panel.
 *
 * Owns all field state, validation, dirty tracking, and conversion to the
 * outgoing request DTO. Transient UI flags (open dropdowns, dialogs) stay in
 * the composable since they are not part of the form data.
 */
@Stable
class CreateKassenbuchEntryFormState {
    // Required fields
    var selectedEntryType: KassenbuchEntryType? by mutableStateOf(null)
    var date: String by mutableStateOf("")
    var description: String by mutableStateOf("")
    var lines: List<EntryLineState> by mutableStateOf(listOf(EntryLineState()))

    // Optional DATEV fields
    var bookingKey: String by mutableStateOf("")
    var counterAccount: String by mutableStateOf("")
    var costCenter1: String by mutableStateOf("")
    var costCenter2: String by mutableStateOf("")
    var quantity: String by mutableStateOf("")
    var discount: String by mutableStateOf("")
    var notes: String by mutableStateOf("")

    // Document attachment (mandatory — server requires a documentId)
    var selectedBelegType: BelegType by mutableStateOf(BelegType.UPLOAD_EIGENBELEG)
    var selectedFileBytes: ByteArray? by mutableStateOf(null)
    var selectedFileName: String? by mutableStateOf(null)

    val invoice: InvoiceMetadataFormState = InvoiceMetadataFormState()

    var validationErrors: List<String> by mutableStateOf(emptyList())

    /** The shop's Beleg-age limit — synced from the server, set by the panel from `SettingManager`. */
    var maxBelegAgeDays: Int by mutableStateOf(KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS)

    /** Why a receipt past the limit is booked late — required only then, and stored with the entry. */
    var lateBookingReason: String by mutableStateOf("")

    // ────────────────────────────────────────────────────────────
    // Mutations
    // ────────────────────────────────────────────────────────────

    fun onEntryTypeSelected(type: KassenbuchEntryType) {
        selectedEntryType = type
        // Smart default: cash expense usually has an external invoice; everything else gets a self-receipt.
        selectedBelegType =
            when (type) {
                KassenbuchEntryType.CASH_EXPENSE -> BelegType.UPLOAD_INVOICE
                else -> BelegType.UPLOAD_EIGENBELEG
            }
        clearFile()
    }

    fun onBelegTypeSelected(type: BelegType) {
        selectedBelegType = type
        clearFile()
        if (type == BelegType.UPLOAD_INVOICE) invoice.reset()
    }

    fun setFile(
        bytes: ByteArray,
        name: String,
    ) {
        selectedFileBytes = bytes
        selectedFileName = name
    }

    fun clearFile() {
        selectedFileBytes = null
        selectedFileName = null
    }

    fun updateLineItem(
        index: Int,
        item: EntryLineState,
    ) {
        lines = lines.toMutableList().also { it[index] = item }
    }

    fun addLineItem() {
        lines = lines + EntryLineState()
    }

    fun removeLineItem(index: Int) {
        if (lines.size > 1) {
            lines = lines.toMutableList().also { it.removeAt(index) }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Derived state
    // ────────────────────────────────────────────────────────────

    /** Typed input: comma or dot. `toDecimalOrZero` (server strings) turned "150,00" into 0. */
    private fun EntryLineState.parsedAmount(): Decimal? = amount.toUserInputDecimalOrNull()

    /** Sum of every line that parses; unparseable or blank lines contribute nothing. */
    val total: Decimal
        get() = lines.mapNotNull { it.parsedAmount() }.sum()

    val totalIsPositive: Boolean
        get() = total > Decimal.ZERO

    /** True when line [index] has text that is not a money amount — that field shows the error. */
    fun lineAmountInvalid(index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        return line.amount.isNotBlank() && line.parsedAmount() == null
    }

    val allLinesValid: Boolean
        get() =
            lines.all {
                val v = it.parsedAmount()
                v != null && !v.isZero && it.vatRate.isNotBlank()
            }

    /**
     * Why [date] is not an acceptable Beleg date on [today], or null — the same
     * [KassenbuchBelegDateRule] the server applies, so the form refuses exactly what the server
     * would. A blank date is fine (the server books today); a malformed one is `validate()`'s job.
     */
    fun belegDateViolation(today: LocalDate): KassenbuchBelegDateViolation? {
        val parsed = KassenbuchBelegDateRule.parseGermanDate(date) ?: return null
        return KassenbuchBelegDateRule.check(parsed, today, maxBelegAgeDays)
    }

    /**
     * True when [date] is past the shop's limit but within a year: the backdating override, which
     * needs a [lateBookingReason] and a manager's authority (the server asks one if the operator
     * lacks it).
     */
    fun needsLateBookingReason(today: LocalDate): Boolean {
        val parsed = KassenbuchBelegDateRule.parseGermanDate(date) ?: return false
        return KassenbuchBelegDateRule.needsOverride(parsed, today, maxBelegAgeDays)
    }

    /** Whether the Beleg date keeps the form from submitting on [today]. */
    fun belegDateBlocksSubmit(today: LocalDate): Boolean =
        when {
            belegDateViolation(today) == null -> false
            needsLateBookingReason(today) -> lateBookingReason.isBlank()
            else -> true
        }

    /**
     * What the fiscal Ladenkasse can pay out right now (F59, the Kassenminus guard) — the book plus
     * the cash takings no Z-Bon has closed yet — or null while unknown. Set by the panel from the
     * ViewModel; the server enforces the same ceiling regardless.
     */
    var availableCash: Decimal? by mutableStateOf(null)

    /** The chosen entry type takes cash out of the drawer. */
    val isOutflow: Boolean
        get() = selectedEntryType?.let { !it.isIncoming } ?: false

    /** A withdrawal larger than the drawer holds — the server would refuse it (Kassenminus). */
    val exceedsAvailableCash: Boolean
        get() = isOutflow && availableCash?.let { total > it } == true

    val canSubmit: Boolean
        get() =
            selectedEntryType != null &&
                allLinesValid &&
                totalIsPositive &&
                !exceedsAvailableCash &&
                description.isNotBlank() &&
                selectedFileBytes != null &&
                !belegDateBlocksSubmit(localToday())

    val isDirty: Boolean
        get() =
            selectedEntryType != null ||
                date.isNotBlank() ||
                description.isNotBlank() ||
                bookingKey.isNotBlank() ||
                counterAccount.isNotBlank() ||
                costCenter1.isNotBlank() ||
                costCenter2.isNotBlank() ||
                quantity.isNotBlank() ||
                discount.isNotBlank() ||
                notes.isNotBlank() ||
                lateBookingReason.isNotBlank() ||
                selectedFileBytes != null ||
                selectedFileName != null ||
                lines.any { it.amount.isNotBlank() } ||
                invoice.isDirty

    // ────────────────────────────────────────────────────────────
    // Output
    // ────────────────────────────────────────────────────────────

    /**
     * Build the request DTO. `documentId` is left null — the ViewModel uploads
     * the attached file first and then fills in the resulting documentId.
     */
    fun buildRequest(): KassenbuchDto.CreateKassenbuchEntryRequestDto {
        val dtoLines =
            lines.map { item ->
                KassenbuchDto.EntryLineDto(
                    kind = item.kind,
                    // The panel hides the picker for a type with no USt-Bezug, so the stored "19"
                    // default must not travel with it; the server pins the same value (rule 8).
                    vatRate = if (selectedEntryType?.carriesVatRate == false) NOT_TAXABLE_RATE else item.vatRate,
                    // EntryLineDto.amount is an exact dot-decimal string on the wire — no Double hop.
                    amount =
                        requireNotNull(item.parsedAmount()) { "buildRequest() called with an invalid line" }
                            .toPlainString(),
                )
            }
        return KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = selectedEntryType!!,
            lines = dtoLines,
            waehrung = "EUR",
            belegDatum = date.trim().ifBlank { null },
            belegtext = description.trim(),
            bu = bookingKey.trim().ifBlank { null },
            gegenkonto = counterAccount.trim().ifBlank { null },
            kost1 = costCenter1.trim().ifBlank { null },
            kost2 = costCenter2.trim().ifBlank { null },
            kostmenge = quantity.trim().ifBlank { null },
            skonto = discount.trim().ifBlank { null },
            nachricht = notes.trim().ifBlank { null },
            documentId = null,
            // Sent only when the override is actually needed; the server ignores it otherwise.
            lateBookingReason = lateBookingReason.trim().takeIf { it.isNotEmpty() && needsLateBookingReason(localToday()) },
        )
    }

    fun fileType(): String =
        when (selectedBelegType) {
            BelegType.UPLOAD_EIGENBELEG -> "EIGENBELEG"
            BelegType.UPLOAD_INVOICE -> "INVOICE"
        }

    fun invoiceMetadataInputOrNull(): InvoiceMetadataInput? =
        if (selectedBelegType == BelegType.UPLOAD_INVOICE && selectedFileBytes != null) {
            invoice.toInput()
        } else {
            null
        }
}

/** The rate submitted for a line of an entry type that carries no VAT (see `KassenbuchEntryType.carriesVatRate`). */
private const val NOT_TAXABLE_RATE = "0"

/** Today on this device, the date the Beleg-date rule is checked against. */
internal fun localToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
