package org.example.project.features.kassenbuch.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/**
 * A cash book's most recent closing, however it was recorded.
 *
 * Two different mechanisms close a cash book — see [CloseKind] — and this type is the unified
 * read model a caller reaches for instead of asking "which kind of book is this" itself. Produced
 * by `CashBookCloseStatusResolver` (`pos-server`); this module only defines the shape.
 */
data class CashBookCloseStatus(
    val cashBookId: Long,
    val closeKind: CloseKind,
    /** When the closing was recorded. */
    val closedAt: LocalDateTime,
    /** The last day the closing covers. */
    val periodEnd: LocalDate,
    /** The book's balance as the closing recorded it. */
    val balanceAtClose: BigDecimal,
    /**
     * What was physically counted, when anything was.
     *
     * Null for [CloseKind.Z_BON]: a Z-Bon computes its closing balance and never asks anyone to
     * count the drawer, so there is no counted figure to report. Only a Kassensturz has one.
     * Modelling this as nullable rather than defaulting it to [balanceAtClose] keeps that
     * asymmetry visible instead of implying a count that never happened.
     */
    val countedBalance: BigDecimal?,
)
