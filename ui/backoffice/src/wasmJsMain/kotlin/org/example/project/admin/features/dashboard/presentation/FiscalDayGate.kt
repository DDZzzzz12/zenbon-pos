package org.example.project.admin.features.dashboard.presentation

import org.example.project.admin.core.presentation.navigation.AdminNavItem
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.StringKey

/**
 * Whether the dashboard composes its two fiscal-day cards, *Heute* (`DayFiguresCard`) and
 * *Tagesabschluss* (`DayCloseCard`).
 *
 * Both open with `GET /api/z-bon/latest/datetime` — the trading day starts at the last Z-Bon —
 * which `ZBonRules` gates on `ZBON_READ`, and then read `/api/statistics/…`, gated on
 * `REPORT_READ`. They used to be composed for everyone, so a principal without `ZBON_READ` (the
 * seeded ORDER_ONLY group, or any custom group an operator builds without it) got a 403 on every
 * dashboard load and the cards rendered as failed. A card the principal cannot read is not composed at all, so its request is
 * never issued — the same hoisted-gate rule as the other dashboard sections. The server keeps
 * refusing on its own (CLAUDE.md rule 8).
 */
internal fun showsFiscalDayCards(policy: AccessPolicy): Boolean = policy.allows(Capability.ZBON_READ) && policy.allows(Capability.REPORT_READ)

/**
 * The *Heute* card's link into the Tagesbericht. Its destination (`AdminNavItem.DAILY_REPORT`)
 * needs `REPORT_ALL_READ`, so a principal holding only `REPORT_READ` would be offered a link that
 * bounces straight back.
 */
internal fun showDailyReportLink(policy: AccessPolicy): Boolean = AdminNavItem.DAILY_REPORT.isVisibleTo(policy)

/**
 * The *latest cash entries* card. `GET /api/v1/kassenbuch/entries/recent` is `CASHBOOK_READ`
 * (`CashBookRules`); without it the card would only ever 403.
 */
internal fun showRecentCashEntries(policy: AccessPolicy): Boolean = policy.allows(Capability.CASHBOOK_READ)

/**
 * Without `REPORT_ALL_READ` the server scopes `/api/statistics/...` to the caller's own sales, so the
 * *Heute* card must not read as the shop's takings.
 */
internal fun dayFiguresTitleKey(showsShopFigures: Boolean): StringKey =
    if (showsShopFigures) AdminStringKeys.DashboardTodayTitle else AdminStringKeys.DashboardTodayTitleOwn

/** The empty-state line of the *Heute* card, worded about the caller's own sales when scoped. */
internal fun dayFiguresNoRevenueKey(showsShopFigures: Boolean): StringKey =
    if (showsShopFigures) AdminStringKeys.DashboardTodayNoRevenue else AdminStringKeys.DashboardTodayNoRevenueOwn
