package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable

/**
 * Kassenbuch — the cash-book entries, one row per logical receipt.
 *
 * The root is [KassenbuchHeaderTable] and not the line items, because the header is what an operator
 * means by "an entry": the `belegNr` is unique to it, a linked PDF hangs off it, and the running
 * balance is stated on it. The line items are to-many from the header — one per VAT rate within one
 * receipt — so they arrive as **aggregates** (`Anzahl Positionen`, `Positionssumme`) and never as
 * columns. Joining them would turn a two-rate receipt into two rows with the header's Betrag and
 * Saldo repeated, which is the row multiplication this whole design exists to prevent.
 *
 * ## Not a Buchungsstapel
 *
 * The DATEV XML export and the `EXTF_Buchungsstapel.csv` already publish this data in the form the
 * tax office and the advisor expect. The registry therefore leaves out `bu`, `gegenkonto`, `kost1`,
 * `kost2` and `waehrung`: those five *are* the DATEV posting fields, and a CSV carrying them beside
 * Belegnummer, Belegdatum and Betrag would be a Buchungsstapel row in all but the metadata line. What
 * is offered instead is the working list — Belegnummer, Belegdatum, Buchungsart, Belegtext, Betrag,
 * Saldo, Korrekturart, Abgeschlossen — which no importer would mistake for a compliance file.
 *
 * ## Two paths to "Kasse", and only one may be used at a time
 *
 * `kassenbuch_header` holds two foreign keys into `cash_book`: `cash_book_id`, the book the entry was
 * posted to, and `counterparty_book_id`, the other side of a Geldtransit. The graph derives both as
 * to-one, so the picker shows **two blocks both headed "Kasse"**. Ticking a column from each is
 * refused when the export is run, by `CustomExportServiceImpl.requireOneRoutePerTable`: one FROM
 * clause holds a table once, so joining it once would make one block's column read the other block's
 * row — a wrong value in a cell with nothing in the file to say so, which is worse than a refusal.
 * Either block on its own works normally. This subject is the first the catalogue ships that can
 * produce that pair; the guard predates it and is now exercised by a test.
 *
 * ## The Zeitraum means when the entry was recorded
 *
 * [ExportSubject.dateColumn] is `created_at`, NOT NULL, stamped per insert. `beleg_datum` is the
 * document date an operator typed and would be the more natural choice — but it is a `varchar(10)`
 * holding "01.02.2026", which no date comparison can range over, so it is offered as a plain text
 * column and the filter uses the column that is actually a date.
 *
 * [ExportSubject.shopColumn] is declared: `kassenbuch_header.shop_id` is NOT NULL, so every run is
 * narrowed to the current shop.
 *
 * Every field is gated by [Capability.CASHBOOK_READ], which is what `CashBookRules` requires of
 * `GET /api/v1/kassenbuch/entries`: an export must never be an easier way to read data than the
 * screen showing it. `CASHBOOK_WRITE` and `CASHBOOK_CLOSE` are the posting rungs and are irrelevant
 * to reading a list.
 */
object KassenbuchSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "kassenbuch",
            label = "Kassenbuch",
            root = KassenbuchHeaderTable,
            // No cast: `kassenbuch_header.created_at` is NOT NULL.
            dateColumn = KassenbuchHeaderTable.createdAt,
            shopColumn = KassenbuchHeaderTable.shopId,
        )
}
