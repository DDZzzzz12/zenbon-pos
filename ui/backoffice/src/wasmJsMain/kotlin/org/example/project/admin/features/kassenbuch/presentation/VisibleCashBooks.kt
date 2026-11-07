package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The books the cash-book switch offers (chips on wide windows, a dropdown on a phone): the active
 * ones, in their configured order and then by name. Deactivated books cannot receive bookings and
 * get no option; their historical entries still resolve to a name from the full list.
 */
fun visibleCashBooks(books: List<CashBookDto.ResponseDto>): List<CashBookDto.ResponseDto> =
    books.filter { it.isActive }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

/**
 * One option's label: the book's name and its own current balance, in that book's currency. The
 * balance is the book's lifetime running balance, never a period figure, and is never summed across
 * books (they are separate ledgers).
 */
fun cashBookOptionLabel(book: CashBookDto.ResponseDto): String = "${book.name} · ${formatMoneyForDisplay(book.currentBalance, book.currency)}"
