package org.example.project.features.customexport.domain.catalogue

/**
 * What no export may ever reach, whatever the catalogue says.
 *
 * The catalogue is an allowlist and is the primary control; this is the second lock. It guards the
 * *curation*: adding a field into forbidden territory fails a build instead of needing a reviewer to
 * notice. An allowlist states what we decided is safe, a denylist states what we already know is
 * dangerous, and for a system holding TSE records and payroll data both are worth having.
 *
 * Every name below is the **SQL** name as Exposed reports it (`Table.tableName`, `Column.name`), not
 * the Kotlin property — the test compares against those, so a Kotlin-cased entry would silently never
 * match, which is worse than no denylist at all.
 *
 * See §5 of the custom export design spec.
 */
object ForbiddenExportTargets {
    /** Nothing in these tables may be exposed, in whole or in part. */
    val TABLES =
        setOf(
            // Session material.
            "refresh_tokens",
            // Pairing secrets.
            "terminal_pairing_codes",
            // Fiscal signatures. A custom extract could resemble a TSE export while not being one,
            // and a correct TSE export already exists.
            "tse_transaction",
            "tse_signing_queue",
            // The Nachsignierung record of each late-signed Vorgang: fiscal outage evidence.
            "tse_late_signing",
        )

    /**
     * Columns denied inside tables that are otherwise exportable.
     *
     * A table-level ban on `employees` would be too blunt — a staff list is one of the four use cases
     * this feature exists for — so the denylist has to work at column granularity too.
     *
     * Keyed `table.column`, lower-cased.
     */
    val COLUMNS =
        setOf(
            "employees.password_hash",
            "employees.pin",
            // The other half of the credential pair. `password_hash` and `pin` were denied as secrets;
            // a username is not a secret, but a file listing every account name in the shop is the
            // half of a login an attacker cannot guess, and no staff list needs it to say who works
            // here. `employee_number` is the identifier that question is keyed by.
            "employees.username",
            // Personnel data with no place in a spreadsheet that gets e-mailed to an advisor. Each is
            // omitted from `ExportTableRegistry`'s `employee_info` entry as well — the allowlist is
            // the primary control — and denied here for the reason `sales_invoice.tse_` is: the
            // allowlist protects them today, and a denylist entry stops a future contributor adding
            // "Gehalt" or "Geburtsdatum" in good faith next year without ever reading that entry.
            //
            // `date_of_birth` is identity-grade: name plus date of birth is what an impersonation
            // needs, and it answers no question a staff list asks. `salary` and `hourly_rate` are
            // remuneration — the payroll data this file's own opening paragraph names — and an
            // export that leaks one person's pay to a colleague is not undoable.
            //
            // The home address (`street`/`city`/`zip_code`/…) is deliberately **not** here: it is
            // left out of the allowlist, but an address list is a request an operator could one day
            // make on purpose, so that stays a decision rather than a ban.
            "employee_info.date_of_birth",
            "employee_info.salary",
            "employee_info.hourly_rate",
            "card_terminal.password",
            // `terminals`, plural: `TerminalTable` is declared `LongIdTable("terminals", "id")`.
            "terminals.device_token_hash",
        )

    /**
     * Column *prefixes* denied inside tables that are otherwise exportable.
     *
     * `sales_invoice` carries the original bill's TSE block inline — a dozen columns holding the
     * signature, the signature counter, the transaction number, the QR payload and the log times.
     * A custom CSV containing them would **resemble fiscal evidence without being it**: it would
     * carry the signature material of a §6 KassenSichV Beleg with none of the structure, ordering or
     * completeness guarantees a real DSFinV-K/TSE export has, and a reader has no way to tell the two
     * apart. A correct TSE export already exists; nothing here should be a second, worse one.
     *
     * A prefix rather than an enumeration because these columns **arrive in batches**. Twelve are
     * there today, TSE 2.0 will add more, and an enumeration protects exactly the twelve while
     * leaving the thirteenth open to whoever registers it next year without ever reading this file.
     * The prefix denies the shape, not the instance.
     *
     * Matched with `startsWith` against the same lower-cased `table.column` key [COLUMNS] uses, so a
     * prefix must be qualified by its table: a bare `tse_` would also deny an unrelated table's
     * column that merely happens to share the naming.
     *
     * ## `bill` and `kassenbuch_header` are here for a *different* reason — state it, do not blur it
     *
     * Neither carries signature material inline. Each declares exactly two `tse_`-prefixed columns —
     * `tse_transaction_id`, a foreign key into `tse_transaction`, and `tse_signing_status`, the
     * Nachsignieren queue state — and the signature, counter, log times and QR payload live in
     * `tse_transaction` itself, which [TABLES] denies outright and which nothing registers, so the
     * schema walk cannot enter it. So the finding on both tables is: **no inline TSE evidence today**.
     *
     * They are denied by prefix anyway because they are the two rows a snapshot would land on next.
     * `bill` *is* the §6 KassenSichV Beleg; `sales_invoice` only carries a copy of the bill's block,
     * and it acquired that block after the fact. TSE 2.0 adds columns. Denying the shape now costs
     * nothing — the two columns that exist are an opaque id and internal plumbing, neither of which
     * belongs in an operator's CSV — and it means a future `bill.tse_signature` fails a build instead
     * of needing a reviewer to notice.
     */
    val COLUMN_PREFIXES =
        setOf(
            "sales_invoice.tse_",
            "bill.tse_",
            "kassenbuch_header.tse_",
        )

    /**
     * Whether [qualified] — a lower-cased `table.column` — is denied, by exact name or by prefix.
     *
     * The single place both rules are applied. A caller consulting [COLUMNS] on its own would honour
     * half the denylist and report a clean result, which is worse than no denylist: it reads as
     * proof, and is not.
     */
    fun deniesColumn(qualified: String): Boolean = qualified in COLUMNS || COLUMN_PREFIXES.any { qualified.startsWith(it) }
}
