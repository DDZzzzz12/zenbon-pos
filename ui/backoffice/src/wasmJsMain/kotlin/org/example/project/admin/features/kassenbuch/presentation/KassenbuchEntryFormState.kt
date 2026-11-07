package org.example.project.admin.features.kassenbuch.presentation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.KassenbuchKeys
import org.example.project.core.utils.KassenbuchBelegDateRule
import org.example.project.core.utils.KassenbuchBelegDateViolation
import org.example.project.money.Decimal
import org.example.project.money.sum
import kotlin.time.Clock

/** `belegDatum` is German `DD.MM.YYYY`, unlike every ISO date elsewhere on this screen. */
private val GERMAN_DATE = Regex("""\d{2}\.\d{2}\.\d{4}""")

/**
 * The "+ Buchung" form, for one non-fiscal cash book.
 *
 * [cashBookId] is captured when the form opens rather than read from the tab at submit time: the two
 * would diverge if the operator switched tabs mid-form, and the booking would silently land in a
 * different ledger than the one it was written for.
 *
 * [documentId] is **mandatory**. `KassenbuchServiceImpl` rejects a null one outright
 * ("documentId is required"), so [canSubmit] refuses before the request rather than letting the
 * operator fill the whole form and be told no by the server.
 *
 * Every amount is a string here and a string on the wire; the only arithmetic is in `Decimal`.
 */
data class KassenbuchEntryFormState(
    val cashBookId: Long,
    val cashBookName: String,
    val entryType: KassenbuchEntryType = KassenbuchEntryType.CASH_EXPENSE,
    /** German `DD.MM.YYYY`. Seeded with today; blank is allowed (the DTO's field is nullable). */
    val belegDatum: String = "",
    val belegtext: String = "",
    val lines: List<EntryLineInput> = listOf(EntryLineInput()),
    val nachricht: String = "",
    val documentId: Long? = null,
    val isSubmitting: Boolean = false,
    /** The shop's Beleg-age limit, loaded from the server with the books. */
    val maxBelegAgeDays: Int = KassenbuchBelegDateRule.DEFAULT_MAX_AGE_DAYS,
    /** The day the Beleg date is judged against; injected so tests are not tied to the clock. */
    val today: LocalDate = localToday(),
    /** Why a receipt past the limit is booked late — required only then, and stored with the entry. */
    val lateBookingReason: String = "",
    /**
     * F59, the Kassenminus guard: what [cashBookId] can pay out — the balance of the book as listed
     * when the form opened (a non-fiscal book holds nothing but its entries). Null while unknown; the
     * server refuses an overdraft with `KASSENBUCH_INSUFFICIENT_CASH` regardless.
     */
    val availableCash: String? = null,
    /**
     * Bumped on every Confirm press the form refused. Field errors are shown from the first press on
     * (validate on press, UI-E2-06) and the screen scrolls to the first one each time. Transient: not
     * part of [isDirtyFrom].
     */
    val validationAttempt: Int = 0,
) {
    /** Signed Beleg total, summed exactly in `Decimal`. Unparseable lines contribute nothing. */
    val total: Decimal
        get() = lines.mapNotNull { it.parsedAmount }.sum()

    /**
     * Blank passes — the field is optional; anything else must be a real `DD.MM.YYYY` date. The shape
     * alone let an impossible `31.02.2026` through to the wire.
     */
    val belegDatumIsUsable: Boolean
        get() = belegDatum.isBlank() || (GERMAN_DATE.matches(belegDatum.trim()) && KassenbuchBelegDateRule.parseGermanDate(belegDatum) != null)

    /** True only for a date that was typed and is malformed, so an untouched field stays quiet. */
    val hasUnusableBelegDatum: Boolean
        get() = belegDatum.isNotBlank() && !belegDatumIsUsable

    /**
     * Why [belegDatum] is not an acceptable Beleg date, or null — the same [KassenbuchBelegDateRule]
     * the server applies: not in the future, not older than [maxBelegAgeDays]. A date inside an
     * already closed period is fine; the entry books into the open period and keeps its date.
     * Blank or malformed yields null here — the format rule above reports those.
     */
    val belegDatumViolation: KassenbuchBelegDateViolation?
        get() =
            KassenbuchBelegDateRule.parseGermanDate(belegDatum)?.let {
                KassenbuchBelegDateRule.check(it, today, maxBelegAgeDays)
            }

    /**
     * Past the shop's limit but within a year: the backdating override. Bookable with a
     * [lateBookingReason]; the server books it at once for a holder of `CASHBOOK_BACKDATE` and asks a
     * manager otherwise.
     */
    val needsLateBookingReason: Boolean
        get() =
            KassenbuchBelegDateRule.parseGermanDate(belegDatum)?.let {
                KassenbuchBelegDateRule.needsOverride(it, today, maxBelegAgeDays)
            } ?: false

    /** Whether the Beleg date keeps the form from submitting. */
    private val belegDatumBlocksSubmit: Boolean
        get() =
            when {
                belegDatumViolation == null -> false
                needsLateBookingReason -> lateBookingReason.isBlank()
                else -> true
            }

    /** The entry takes cash out of the book. */
    val isOutflow: Boolean
        get() = !entryType.isIncoming

    /** The available cash, parsed, or null while unknown. */
    val availableCashAmount: Decimal?
        get() = availableCash?.let { Decimal.parseOrNull(it) }

    /** A withdrawal larger than the book holds — the book may not go below zero (Kassenminus). */
    val exceedsAvailableCash: Boolean
        get() = isOutflow && availableCashAmount?.let { total > it } == true

    /**
     * Every line carries the VAT rate the server's matrix demands. A blank one is not a rate.
     * A type with no USt-Bezug (`KassenbuchEntryType.carriesVatRate`, e.g. Geldtransit) takes none.
     */
    private val everyLineHasVatRate: Boolean
        get() = !entryType.carriesVatRate || lines.all { it.hasVatRate }

    /**
     * Everything [canSubmit] requires **except** the VAT rates.
     *
     * Split out so the dialog can tell the operator *which* of the two mandatory-but-invisible rules
     * is still holding the button down — the same job the Beleg warning does for [documentId].
     */
    private val submittableApartFromVatRate: Boolean
        get() =
            documentId != null &&
                belegtext.isNotBlank() &&
                belegDatumIsUsable &&
                !belegDatumBlocksSubmit &&
                lines.isNotEmpty() &&
                lines.all { it.hasUsableAmount } &&
                total > Decimal.ZERO &&
                !exceedsAvailableCash &&
                !isSubmitting

    /**
     * Mirrors `CreateKassenbuchEntryRequestDto.validate()`, which is itself the server's matrix.
     *
     * The total must be **positive**: the entry type carries the direction, so a negative Beleg
     * total means the operator picked the opposite type and the server rejects it.
     */
    val canSubmit: Boolean
        get() = submittableApartFromVatRate && everyLineHasVatRate

    /**
     * True when the **only** thing left blocking confirm is a missing VAT rate.
     *
     * A blank `USt-Satz` is easy to overlook — the field is narrow, sits beside the amount, and has
     * no error state of its own until something is typed into it. Without this the operator fills the
     * form, uploads the Beleg, watches that warning disappear, and is left with a dead button and no
     * reason for it. Deliberately silent while anything *else* is also missing: two red lines at once
     * would say less than one.
     */
    val hasMissingVatRate: Boolean
        get() = submittableApartFromVatRate && !everyLineHasVatRate

    /**
     * What a Confirm press flags, keyed by [EntryFormFields] keys in on-screen order: the fields at
     * fault with their message. Errors that belong to no field (no Beleg yet, a total that is not
     * positive, a withdrawal larger than the book) are not in here; [formLevelErrors] carries them.
     */
    fun fieldErrors(): Map<String, StringKey> =
        buildMap {
            when {
                hasUnusableBelegDatum -> put(EntryFormFields.BELEG_DATUM, AdminStringKeys.KassenbuchEntryDateInvalid)
                belegDatumViolation == KassenbuchBelegDateViolation.FUTURE && !needsLateBookingReason ->
                    put(EntryFormFields.BELEG_DATUM, KassenbuchKeys.documentDateInFuture)
                belegDatumViolation == KassenbuchBelegDateViolation.TOO_OLD && !needsLateBookingReason ->
                    put(EntryFormFields.BELEG_DATUM, KassenbuchKeys.documentDateTooOld)
            }
            if (needsLateBookingReason && lateBookingReason.isBlank()) put(EntryFormFields.LATE_REASON, CommonKeys.fieldRequired)
            if (belegtext.isBlank()) put(EntryFormFields.BELEGTEXT, CommonKeys.fieldRequired)
            lines.forEachIndexed { index, line ->
                when {
                    line.amount.isBlank() -> put(EntryFormFields.amount(index), CommonKeys.fieldRequired)
                    !line.hasUsableAmount -> put(EntryFormFields.amount(index), KassenbuchKeys.amountNotANumber)
                }
                if (entryType.carriesVatRate && !line.hasVatRate) put(EntryFormFields.vatRate(index), CommonKeys.fieldRequired)
            }
        }

    /** The field keys in on-screen order, for scrolling to the first error. */
    fun fieldOrder(): List<String> =
        listOf(EntryFormFields.BELEG_DATUM, EntryFormFields.LATE_REASON, EntryFormFields.BELEGTEXT) +
            lines.indices.flatMap { listOf(EntryFormFields.amount(it), EntryFormFields.vatRate(it)) }

    /** Refusals that belong to no single field, each shown once as a line above the Beleg upload. */
    fun formLevelErrors(): List<StringKey> =
        buildList {
            if (lines.all { it.hasUsableAmount } && total <= Decimal.ZERO) add(KassenbuchKeys.totalMustBePositive)
        }

    /**
     * True when anything the operator can edit differs from [baseline], the form as it opened.
     * Transient flags and the context loaded around the form (`isSubmitting`, the Beleg-age limit,
     * available cash) are deliberately not compared: edit-then-undo is clean.
     */
    fun isDirtyFrom(baseline: KassenbuchEntryFormState): Boolean =
        entryType != baseline.entryType ||
            belegDatum != baseline.belegDatum ||
            belegtext != baseline.belegtext ||
            lines != baseline.lines ||
            nachricht != baseline.nachricht ||
            documentId != baseline.documentId ||
            lateBookingReason != baseline.lateBookingReason

    /**
     * The outgoing request. `cashBookId` is what makes this a non-fiscal booking rather than one
     * into the Ladenkasse — the DTO's null default means "the shop's fiscal book".
     */
    fun toRequest(): KassenbuchDto.CreateKassenbuchEntryRequestDto =
        KassenbuchDto.CreateKassenbuchEntryRequestDto(
            entryType = entryType,
            lines =
                lines.map { line ->
                    KassenbuchDto.EntryLineDto(
                        vatRate = if (entryType.carriesVatRate) line.vatRate.trim() else NOT_TAXABLE_RATE,
                        // Exact decimal string, never a Double: this is the amount that is booked.
                        amount = line.parsedAmount?.toPlainString().orEmpty(),
                    )
                },
            belegDatum = belegDatum.trim().ifBlank { null },
            belegtext = belegtext.trim(),
            nachricht = nachricht.trim().ifBlank { null },
            documentId = documentId,
            cashBookId = cashBookId,
            // Sent only when the override is actually needed; the server ignores it otherwise.
            lateBookingReason = lateBookingReason.trim().takeIf { it.isNotEmpty() && needsLateBookingReason },
        )
}

private fun localToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** The rate submitted for a line of an entry type that carries no VAT (see `KassenbuchEntryType.carriesVatRate`). */
private const val NOT_TAXABLE_RATE = "0"
