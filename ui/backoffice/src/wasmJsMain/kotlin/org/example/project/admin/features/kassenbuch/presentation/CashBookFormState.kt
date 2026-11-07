package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.money.Decimal

/** The DATEV Sachkonto column is at most five digits — the same shape the DTO validates. */
private val DATEV_ACCOUNT = Regex("""\d{1,5}""")

/** Matches `CashBookDto`'s own limit, so the form refuses what the server would refuse. */
private const val MAX_NAME_LENGTH = 60

/**
 * The create/edit form for one cash book.
 *
 * There is deliberately **no fiscal toggle**: `CashBookDto.CreateRequestDto` carries no `isFiscal`
 * field and `UpdateRequestDto` cannot flip one, because the server hardcodes `isFiscal = false` on
 * create and the shop's single fiscal Ladenkasse is provisioned by the migration. Offering a switch
 * that silently does nothing would be worse than not offering it. [isFiscal] is therefore read-only
 * context, carried so the deactivation guard below can see it.
 *
 * [currentBalance] is the server's exact decimal string and is compared in `Decimal`, never as a
 * `Double` and never by string equality — `"0"`, `"0.00"` and `"0.0000"` are all a zero balance and
 * a textual comparison would let two of the three block a legitimate deactivation.
 */
data class CashBookFormState(
    /** `null` while creating; the book's id while editing. */
    val bookId: Long? = null,
    val isFiscal: Boolean = false,
    val name: String = "",
    val datevAccount: String = "",
    val kost1: String = "",
    val kost2: String = "",
    val closeInterval: CashBookCloseInterval = CashBookCloseInterval.MANUAL,
    val isActive: Boolean = true,
    val sortOrder: String = "0",
    /** The balance as the server reported it; `"0"` for a book that does not exist yet. */
    val currentBalance: String = "0",
    /** The book's ISO currency from the response, so [currentBalance] renders through `:platform-money`. */
    val currency: String = "",
    val isSubmitting: Boolean = false,
    /**
     * The OTHER loaded books' names, normalized by [normalizedBookName] — what this form's name may
     * not collide with. Built by [takenBookNames] when the form opens.
     */
    val takenNames: Set<String> = emptySet(),
    /**
     * A name the server refused with `CASH_BOOK_NAME_ALREADY_TAKEN`. The server also counts
     * deactivated books, which the loaded list does not carry, so its 409 is the only way the form
     * can learn about those; remembering the refused name keeps the field flagged until it changes.
     */
    val serverRefusedName: String? = null,
    /**
     * Bumped on every Save click that is refused; the dialog reveals the first error on each bump.
     * Also what turns an **empty** required field into an error: before the first attempt an
     * untouched field is not one. Transient — [isDirtyFrom] does not compare it.
     */
    val validationAttempt: Int = 0,
) {
    val isEdit: Boolean get() = bookId != null

    private val isNameUsable: Boolean get() = name.isNotBlank() && name.length <= MAX_NAME_LENGTH

    /**
     * Whether another book already carries [name] — compared the way `CashBookServiceImpl` compares
     * (trimmed, case-insensitive), so the form refuses what the server would refuse with a 409.
     */
    val isNameTaken: Boolean
        get() {
            val candidate = normalizedBookName(name)
            return candidate.isNotEmpty() &&
                (candidate in takenNames || serverRefusedName?.let { normalizedBookName(it) } == candidate)
        }

    private val isDatevAccountUsable: Boolean get() = datevAccount.matches(DATEV_ACCOUNT)

    val sortOrderValue: Int? get() = sortOrder.trim().toIntOrNull()?.takeIf { it >= 0 }

    /** An untouched field is not an error — only something typed that cannot be used is. */
    val hasUnusableName: Boolean get() = name.isNotEmpty() && !isNameUsable

    val hasUnusableDatevAccount: Boolean get() = datevAccount.isNotEmpty() && !isDatevAccountUsable

    val hasUnusableSortOrder: Boolean get() = sortOrder.isNotEmpty() && sortOrderValue == null

    /** Whether the fields hold a book the server would accept. Not the Save button's state: Save validates on click. */
    val canSubmit: Boolean
        get() = !isSubmitting && isNameUsable && !isNameTaken && isDatevAccountUsable && sortOrderValue != null

    /**
     * The message per invalid field, computed live: something typed that cannot be used shows at
     * once (as before), an empty required field only after the first refused Save.
     */
    val fieldErrors: Map<CashBookField, StringKey>
        get() {
            val attempted = validationAttempt > 0
            return buildMap {
                when {
                    // "Name is required and may be at most 60 characters" covers the empty case too.
                    hasUnusableName || (attempted && name.isEmpty()) -> put(CashBookField.NAME, AdminStringKeys.KassenbuchBookNameInvalid)
                    isNameTaken -> put(CashBookField.NAME, AdminStringKeys.KassenbuchBookNameTaken)
                }
                when {
                    hasUnusableDatevAccount -> put(CashBookField.DATEV_ACCOUNT, AdminStringKeys.KassenbuchBookDatevAccountInvalid)
                    attempted && datevAccount.isEmpty() -> put(CashBookField.DATEV_ACCOUNT, CommonKeys.fieldRequired)
                }
                when {
                    hasUnusableSortOrder -> put(CashBookField.SORT_ORDER, AdminStringKeys.KassenbuchBookSortOrderInvalid)
                    attempted && sortOrder.isEmpty() -> put(CashBookField.SORT_ORDER, CommonKeys.fieldRequired)
                }
            }
        }

    /**
     * Why the active switch may not be turned off, or `null` when it may be.
     *
     * Mirrors `CashBookServiceImpl.requireDeactivatable` exactly: the fiscal book is refused on
     * `isFiscal` alone whatever its balance, and a non-fiscal one only while it still holds cash.
     * A balance that cannot be parsed counts as non-zero — refusing a deactivation the server might
     * have allowed is recoverable, stranding cash is not.
     */
    val deactivationBlock: DeactivationBlock?
        get() =
            when {
                isFiscal -> DeactivationBlock.FISCAL
                !isZeroBalance(currentBalance) -> DeactivationBlock.NON_ZERO_BALANCE
                else -> null
            }

    val canDeactivate: Boolean get() = deactivationBlock == null

    /**
     * True when a field the operator can edit differs from [baseline], the form as it opened.
     * Submitting/refusal flags and the collision set are not compared: edit-then-undo is clean.
     */
    fun isDirtyFrom(baseline: CashBookFormState): Boolean =
        name != baseline.name ||
            datevAccount != baseline.datevAccount ||
            kost1 != baseline.kost1 ||
            kost2 != baseline.kost2 ||
            closeInterval != baseline.closeInterval ||
            isActive != baseline.isActive ||
            sortOrder != baseline.sortOrder

    fun toCreateRequest(): CashBookDto.CreateRequestDto =
        CashBookDto.CreateRequestDto(
            name = name.trim(),
            datevAccount = datevAccount.trim(),
            closeInterval = closeInterval,
            kost1 = kost1.trim().ifBlank { null },
            kost2 = kost2.trim().ifBlank { null },
        )

    fun toUpdateRequest(): CashBookDto.UpdateRequestDto =
        CashBookDto.UpdateRequestDto(
            name = name.trim(),
            datevAccount = datevAccount.trim(),
            closeInterval = closeInterval,
            kost1 = kost1.trim().ifBlank { null },
            kost2 = kost2.trim().ifBlank { null },
            isActive = isActive,
            sortOrder = sortOrderValue ?: 0,
        )

    companion object {
        /** Opens the form on an existing book, carrying its balance so the guard can read it. */
        fun of(
            book: CashBookDto.ResponseDto,
            takenNames: Set<String> = emptySet(),
        ): CashBookFormState =
            CashBookFormState(
                takenNames = takenNames,
                bookId = book.id,
                isFiscal = book.isFiscal,
                name = book.name,
                datevAccount = book.datevAccount,
                kost1 = book.kost1.orEmpty(),
                kost2 = book.kost2.orEmpty(),
                closeInterval = book.closeInterval,
                isActive = book.isActive,
                sortOrder = book.sortOrder.toString(),
                currentBalance = book.currentBalance,
                currency = book.currency,
            )
    }
}

/**
 * True when [amount] is exactly zero, decided in `Decimal`.
 *
 * Never compare a server money string textually: `"0"`, `"0.00"` and `"0.0000"` are the same
 * balance and only one of them equals `"0"`. An unparseable value is reported as **not** zero, so a
 * malformed balance blocks a deactivation rather than waving it through.
 */
internal fun isZeroBalance(amount: String): Boolean = Decimal.parseOrNull(amount)?.isZero == true

/** A book name as the server compares it: trimmed and case-folded. */
internal fun normalizedBookName(name: String): String = name.trim().lowercase()

/** The normalized names of every loaded book except [exceptBookId] — the form's collision set. */
internal fun takenBookNames(
    books: List<CashBookDto.ResponseDto>,
    exceptBookId: Long?,
): Set<String> = books.filter { it.id != exceptBookId }.map { normalizedBookName(it.name) }.toSet()
