package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.ExportToggle
import org.jetbrains.exposed.v1.core.eq

/**
 * Kassenbelege — the receipts the till printed, one row per bill. The shop's own working list of what
 * was sold and when, for a month an operator wants to look at in a spreadsheet.
 *
 * ## The name, and why it is not "Rechnungen"
 *
 * The adminApp does say "Rechnungen" in the Z-Bon and table-session panels — but it says exactly the
 * same word for the *supplier* invoice worklist (`NavInvoices`), and this catalogue already ships
 * **Eingangsrechnungen** and **Ausgangsrechnungen** as their own Datenbereiche. A seventh area called
 * "Rechnungen" would name a third thing with the word already doing two jobs beside it. The manual
 * settles it in the operator's own vocabulary: chapter 6 heads the section *"Beleg und formelle
 * Rechnung"* and keeps the two apart, and the fiscal documentation calls this row a *Kassenbeleg*
 * throughout. So: **Kassenbelege**.
 *
 * ## Not a compliance export
 *
 * DSFinV-K and the TSE export already publish these rows correctly, completely and in a defined
 * order. Nothing here is shaped to resemble either: no `tse_` column is registered (they are denied
 * by prefix — see `ForbiddenExportTargets.COLUMN_PREFIXES`, which also records that this table
 * carries no signature material inline today), the column set is a short sales list rather than a
 * DSFinV-K row, and the file name a definition produces can never begin `EXTF`.
 *
 * ## The Zeitraum means when the bill was closed
 *
 * [ExportSubject.dateColumn] is `end_time`, NOT NULL, the moment the bill was settled. `start_time`
 * is when the table sat down; ranging over it would put a sale into the day service began rather than
 * the day it was paid, which differs across midnight exactly where it matters most.
 *
 * ## The one toggle
 *
 * **Ohne Schulungsbelege** — `is_training = false`. A training bill is not a sale and is not fiscally
 * relevant, so a column of Bruttobeträge that silently includes them totals to a number nobody can
 * reconcile. It is a toggle rather than a [ExportSubject.basePredicate] for the reason
 * [SalesInvoiceSubject] gives: a shorter file looks like a shorter file, not like a filter nobody
 * asked for. The `Schulungsbeleg` column is registered next to it so the operator can see which is
 * which when the toggle is off.
 *
 * ## No shop column
 *
 * `bill` carries no shop of its own — the terminal does, and the bill points at the terminal only
 * optionally. Stated rather than omitted, for the reason [ProductSubject] gives: a null
 * [ExportSubject.shopColumn] must always be a decision somebody wrote down.
 *
 * ## The row cap will bite here
 *
 * This is the largest table in the schema by row count, and a busy year of it is well past the
 * runner's hundred-thousand-row cap. A truncated run reports `truncated = true` and the mask says so;
 * the operator's remedy is a narrower Zeitraum, not a bigger cap.
 *
 * Every field of the root is gated by [Capability.BILL_READ], which is what `BillRules` requires of
 * `GET /api/v1/bills`: an export must never be an easier way to read data than the screen showing it.
 */
object BillSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "bill",
            label = "Kassenbelege",
            // No cast, unlike the AP invoice's `issueDate`: `bill.end_time` is NOT NULL.
            dateColumn = BillTable.endTime,
            root = BillTable,
            shopColumn = null,
            toggles =
                listOf(
                    ExportToggle(
                        key = "bill.exclude_training",
                        label = "Ohne Schulungsbelege",
                        predicate = { BillTable.isTraining eq false },
                    ),
                ),
        )
}
