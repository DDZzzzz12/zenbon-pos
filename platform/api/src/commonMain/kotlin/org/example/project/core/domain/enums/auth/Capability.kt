package org.example.project.core.domain.enums.auth

import kotlinx.serialization.Serializable

/**
 * The authorization vocabulary. Every route rule in `RoutePermissions` requires exactly one of
 * these, and a permission group is a set of them.
 *
 * **This enum is deliberately code, not data.** A capability no route references is a dead checkbox
 * in the admin UI — an operator ticks it, believes access was granted, and gets a 403. The inverse
 * is worse: because the route registry denies by default, a rule referencing a capability someone
 * deleted becomes a silent outage across a whole feature. Holding the vocabulary in code makes both
 * cases a compile error or a failing test. Permission groups (Stage 2) compose these; they never
 * define them.
 *
 * Three kinds live here:
 *  - **Domain `_READ`/`_WRITE` pairs**, for feature areas where read-only access is a genuine use
 *    case — invoices, documents, accounting, the cash book, reports. This split is what makes a
 *    read-only `ACCOUNTANT` expressible at all, and it is mechanical because the route registry
 *    already keys on HTTP method.
 *  - **Domain `_READ`/`_MANAGE` pairs**, for feature areas that divide instead into "the till uses
 *    it" and "the back office runs it" — catalog, promotions, terminals, card terminals, shop info,
 *    Z-Bon. Some of their *reads* sit on the back-office side, and no role needs those reads without
 *    the corresponding writes, so `_MANAGE` covers both rather than inventing a third tier.
 *  - **Operation** capabilities for individually dangerous actions, never folded into a domain's
 *    `_WRITE` or `_MANAGE`. Voiding a bill is not "writing a bill".
 *
 * A domain has `_WRITE` **or** `_MANAGE`, never both. Read-only domains (`REPORT`, `AUDIT`, `SETUP`)
 * have neither rather than an unused member.
 *
 * See `docs/superpowers/specs/2026-08-21-capability-permissions-design.md`.
 */
@Serializable
enum class Capability(
    val assignability: Assignability = Assignability.ASSIGNABLE,
) {
    // ================================================================
    // Domain capabilities — read/write pairs
    // ================================================================

    /**
     * Reading the menu the till renders: products, categories, extras, choice groups, Pfand types,
     * payment types, VAT rates.
     */
    CATALOG_READ,

    /**
     * Managing that catalog — editing it, plus the back-office-only reads over it
     * (`payment-types/active`, `payment-types/statistics`).
     *
     * A `_MANAGE` tier rather than `_WRITE` because this domain divides into "the till uses it" and
     * "the back office runs it", and a handful of its *reads* sit on the back-office side. Nobody
     * needs to read payment-type statistics without being able to edit payment types, so collapsing
     * restricted reads and writes into one capability costs nothing and keeps the vocabulary small.
     */
    CATALOG_MANAGE,

    /**
     * Orders and order items.
     *
     * The manager-gated cancellation and transfer *history* is [AUDIT_READ], not an order
     * capability: it is a record of what was reversed, which is audit evidence rather than order
     * operation.
     */
    ORDER_READ,
    ORDER_WRITE,

    /**
     * Tables, room elements, location types and table sessions.
     *
     * Floor-plan *editing* is [CONFIG_WRITE] — the layout is shop configuration, not a till
     * operation — and session repair is [TABLE_REPAIR].
     */
    TABLE_READ,
    TABLE_WRITE,

    /**
     * Reservations as staff take and amend them at the till.
     *
     * Reservation *rules* and opening hours are [CONFIG_READ]/[CONFIG_WRITE]: they configure how
     * reservations behave rather than being reservations.
     */
    RESERVATION_READ,
    RESERVATION_WRITE,

    /**
     * Looking a customer up and attaching them to a table session, and creating one at the till.
     *
     * Deliberately NOT the unrestricted `GET /api/customers` list — see [CUSTOMER_LIST]. The till
     * searches for a specific person; enumerating the whole customer base is back-office work, and
     * the distinction is defect D4's largest remaining piece as well as the one with GDPR weight.
     */
    CUSTOMER_READ,
    CUSTOMER_WRITE,

    /** Enumerating the entire customer base — `GET /api/customers`, `GET /api/customer-addresses`. */
    CUSTOMER_LIST,

    /** Bills. Voiding and payment changes are separate operations below. */
    BILL_READ,
    BILL_WRITE,

    /**
     * The till's voucher work: validating a code and reading one voucher's redemption history.
     *
     * Deliberately NOT the reporting endpoints — see [VOUCHER_REPORT]. Splitting them is what stops
     * a terminal login reading shop-wide voucher statistics (defect D4).
     */
    VOUCHER_READ,
    VOUCHER_WRITE,

    /** Shop-wide voucher reporting: statistics, created-by-date, redeemed-by-date. Back office. */
    VOUCHER_REPORT,

    /** Promotions. */
    PROMOTION_READ,

    /** Manage tier — the till reads active promotions; the back office creates and reports on them. See [CATALOG_MANAGE]. */
    PROMOTION_MANAGE,

    /**
     * Kassenbuch entries, cash books and Geldtransit between books.
     *
     * Entirely manager-gated today — nothing here is till work. Kept as a strict `_READ`/`_WRITE`
     * pair rather than `_MANAGE` because a read-only ACCOUNTANT reading the cash book without being
     * able to post to it is a Stage 3 requirement.
     */
    CASHBOOK_READ,
    CASHBOOK_WRITE,

    /** The one `Role(ADMIN)` Kassenbuch route, which cannot share the manager-level pair. */
    CASHBOOK_ADMIN,

    /** DATEV log and cadence, Zuordnung, match suggestions, Buchungsbeginn, allocations. */
    ACCOUNTING_READ,
    ACCOUNTING_WRITE,

    /** Document management (Belege). Deletion is a separate reserved operation below. */
    DOCUMENT_READ,
    DOCUMENT_WRITE,

    /** The normalized invoice aggregate, incoming and outgoing. */
    INVOICE_READ,
    INVOICE_WRITE,

    /** Suppliers, recurring obligations, our own bank accounts, bank statements. */
    CREDITOR_READ,
    CREDITOR_WRITE,

    /** Debtors (Ausgangsrechnungen counterparties). */
    DEBTOR_READ,
    DEBTOR_WRITE,

    /** Employee records. Credential resets are a separate operation below. */
    EMPLOYEE_READ,
    EMPLOYEE_WRITE,

    /** Time tracking entries belonging to other employees. Own entries are [SELF_READ]/[SELF_WRITE]. */
    TIMETRACKING_READ,
    TIMETRACKING_WRITE,

    /**
     * Cost centers and wage types.
     *
     * Reads are `Authenticated` today — the till offers a cost centre when clocking in — while every
     * write is `Role(ADMIN)`, so the pair straddles the widest gap in the vocabulary.
     */
    PAYROLL_READ,
    PAYROLL_WRITE,

    /**
     * Every employee's time entries, shop-wide — `GET /api/timetracking/all`.
     *
     * `Role(ADMIN)` today, unlike the manager-level team view ([TIMETRACKING_READ]), so it cannot
     * share that capability.
     */
    TIMETRACKING_ALL_READ,

    /** The timetracking DATEV export and its preview — manager-gated payroll handoff. */
    TIMETRACKING_DATEV_EXPORT,

    /** Printer discovery, printer locations, print job history. */
    PRINTER_READ,

    /**
     * Driving this shop's printers: configuring a location, the test print, `print-pdf` — and
     * reprinting a settled bill (`POST /api/v1/bills/{billId}/print`), which is floor work rather
     * than the fiscal act `BILL_WRITE` gates. See `BillRules`.
     */
    PRINTER_WRITE,

    /** POS terminals: pairing, registration, deactivation. */
    TERMINAL_READ,

    /** Manage tier — a station reads its own registration; the back office lists and administers all of them. See [CATALOG_MANAGE]. */
    TERMINAL_MANAGE,

    /**
     * A station registering ITSELF — `POST /api/v1/terminals/register`.
     *
     * A terminal-level write, so it cannot be [TERMINAL_MANAGE] (back office) nor [TERMINAL_READ]
     * (a write must not carry a `_READ`).
     */
    TERMINAL_SELF_REGISTER,

    /** Card payment terminals. */
    CARD_TERMINAL_READ,

    /**
     * Manage tier — provisioning card terminals (create, edit, delete). `Role(ADMIN)` today.
     * See [CATALOG_MANAGE].
     */
    CARD_TERMINAL_MANAGE,

    /**
     * Operating a provisioned card terminal — connect, disconnect, run diagnosis, preview totals.
     *
     * Manager-gated, between the till's [CARD_TERMINAL_READ] and admin-only
     * [CARD_TERMINAL_MANAGE]: this family carries three distinct access levels, not two.
     */
    CARD_TERMINAL_OPERATE,

    /** Shop master data and logo. */
    SHOP_INFO_READ,

    /** Manage tier — the till syncs shop data; the back office edits it. See [CATALOG_MANAGE]. */
    SHOP_INFO_MANAGE,

    /**
     * Shop configuration keys under `/api/v1/config`, plus the floor-plan layout.
     *
     * The twelve config `PUT`s carry [SYSTEM_CONFIG] rather than [CONFIG_WRITE], because they are
     * `PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER)` and must stay reachable by grant holders.
     * [CONFIG_WRITE] gates the config-shaped routes that are plain `Role(MANAGER)` — floor-plan
     * edits, reservation rules, opening hours.
     */
    CONFIG_READ,
    CONFIG_WRITE,

    /**
     * Gated the POS-ERP cash-ledger integration, which was **removed** in `90cd23a92` (Flyway
     * V116). It therefore gates no route today, and `NoOrphanCapabilityTest` lists it as a
     * deliberate orphan rather than a dead checkbox.
     *
     * Retained on purpose, like the `OCR_LLM` provenance value and the `QUICK_SALE` enums: V110
     * already granted it to the ADMIN permission group and a committed migration is never edited,
     * so deleting the constant would leave `permission_group_capability` rows naming a capability
     * this enum cannot parse.
     *
     * Do not attach it to a new route — pick a capability that describes that route instead.
     */
    ERP_INTEGRATION_ADMIN,

    /** The Kassen-Meldung (§146a fiscal registration report) and its PDF — manager-gated reads. */
    KASSEN_MELDUNG_READ,

    /**
     * The notification SSE stream.
     *
     * Read-only: notifications are produced by the server, never posted by a client, so there is no
     * `NOTIFICATION_WRITE` to pair with this.
     */
    NOTIFICATION_READ,

    /** Scheduled jobs. */
    SCHEDULED_JOB_READ,
    SCHEDULED_JOB_WRITE,

    /**
     * Seeing the software-update state: which version is running, what the nightly check found,
     * whether an artifact is staged, and how the update source is configured.
     *
     * Read-only in the strictest sense — the settings read behind it deliberately carries no token,
     * only a masked preview, so holding this never exposes the GitHub credential.
     */
    UPDATE_READ,

    /**
     * Changing where updates come from and installing one — the settings write, the token, and the
     * manual check/stage/apply.
     *
     * A `_MANAGE` tier rather than `_WRITE` because this domain divides the way [CATALOG_MANAGE]
     * does: the shop *reads* its update state, and exactly one back-office principal *runs* it.
     * There are no restricted reads to separate out, and no role needs to store an update token
     * without also being allowed to install what it fetches, so a third tier would be a dead
     * checkbox.
     *
     * Deliberately narrower than [UPDATE_READ] when seeded: applying swaps the running binary,
     * runs Flyway on the live fiscal database and restarts the process. That is the same weight as
     * [BACKUP_RESTORE] or [SYSTEM_CONFIG], and belongs with whoever administers the installation
     * rather than with whoever runs the restaurant.
     */
    UPDATE_MANAGE,

    /**
     * Filing a problem report as a GitHub issue, and previewing the scrubbed report first.
     *
     * Never confers the credential: storing or clearing the token and choosing the target
     * repository is [ISSUE_REPORT_MANAGE]. The split exists so that a role allowed to *report*
     * cannot repoint where reports (and the diagnostics in them) are sent.
     */
    ISSUE_REPORT_CREATE,

    /**
     * Choosing the target repository for problem reports and storing or clearing the GitHub token
     * used to file them. Holding it is holding a credential, so it is ADMIN-only when seeded.
     */
    ISSUE_REPORT_MANAGE,

    /** Login sessions and session policy. Revocation is a separate operation below. */
    SESSION_READ,
    SESSION_WRITE,

    /** Contracts (Verträge). */
    CONTRACT_READ,
    CONTRACT_WRITE,

    /**
     * List and run custom export definitions somebody else built.
     *
     * The bookkeeper who runs the monthly extract every month is not the person who designed it. Safe
     * to hold alone: running re-checks every field against **the runner's** capabilities and refuses
     * rather than blanking a column, so a reader cannot reach data through a definition written by
     * someone with more rights.
     */
    CUSTOM_EXPORT_READ,

    /**
     * Build, edit and run custom export definitions.
     *
     * `_MANAGE` rather than `_WRITE`: the convention in this file is one or the other per domain,
     * never both. Holding it admits a principal to the feature; **what they may actually export is
     * decided per field by the catalogue**, against the capabilities they hold for that data.
     */
    CUSTOM_EXPORT_MANAGE,

    /**
     * The caller's OWN profile, PIN, language, schedule and time entries.
     *
     * Distinct from [EMPLOYEE_READ] because the eight "self or manager" routes need a floor that
     * every employee holds, with the handler proving row ownership on top.
     */
    SELF_READ,
    SELF_WRITE,

    // ================================================================
    // Domain capabilities — read-only areas
    // ================================================================

    /**
     * Sales, financial, catalog and promotion statistics.
     *
     * Deliberately NOT the training report: that is manager-gated today while these are open to
     * every authenticated principal, and one capability cannot carry two access levels without
     * widening one of them.
     */
    REPORT_READ,

    /**
     * Everyone's figures, not just your own — and the `employee_id` filter that names whose.
     *
     * The same split as [TIMETRACKING_READ] / [TIMETRACKING_ALL_READ], for the same reason: "may
     * see a report" and "may see other people's" are different questions, and one capability cannot
     * answer both without widening one of them.
     *
     * Until now the answer came from `role.isManagerOrHigher()` inside `resolveTargetEmployee`, so
     * what an ACCOUNTANT or AUDITOR saw depended on whichever `role` value happened to be on their
     * employee row — the field the group model exists to stop deciding things. An auditor carrying
     * role CASHIER was scoped to *their own* figures, of which an auditor has none.
     */
    REPORT_ALL_READ,

    /** The training-mode report — manager-gated, unlike the statistics above. */
    TRAINING_REPORT_READ,

    /** Reading the Z-Bon list and the latest close time — the till shows these. */
    ZBON_READ,

    /**
     * Manage tier — an individual Z-Bon, its history, its PDF and reprinting it: back-office work,
     * unlike the till's [ZBON_READ]. Closing the day is the RESERVED [ZBON_CLOSE].
     */
    ZBON_MANAGE,

    /**
     * Entry logs and the transaction audit trail — compliance evidence, manager-gated.
     *
     * Deliberately NOT the `/api/changes` sync feed: that is `Authenticated` today and every client
     * polls it, so sharing this capability would open the audit trail to every terminal.
     */
    AUDIT_READ,

    /** The client sync change feed — infrastructure every client polls, not audit evidence. */
    SYNC_READ,

    /** Release notes from `changelog_release`. */
    CHANGELOG_READ,

    /**
     * Setup readiness — fiscal terminal and TSE commissioning state.
     *
     * There is deliberately no `SETUP_INITIALIZE` counterpart: `initialize` and `restore-backup`
     * are `Public` because they must work before any employee exists, so no capability can gate
     * them and one would only be a dead checkbox.
     */
    SETUP_READ,

    // ================================================================
    // Operation capabilities — assignable
    // ================================================================

    /** Delete/reverse a bill (Storno). Fiscally significant: produces a TSE-signed reversal. */
    BILL_VOID,

    /** Change a bill's payment type after the fact (Zahlartwechsel). */
    BILL_CHANGE_PAYMENT,

    /** Cancel order items (Storno of a Bestellung line). */
    ORDER_ITEM_CANCEL,

    /**
     * Destructive reservation operations — deleting a reservation, unassigning its tables.
     *
     * Separate from [RESERVATION_WRITE] because staff take and amend reservations at the till while
     * these two are manager-gated, and one capability cannot hold both levels.
     */
    RESERVATION_DELETE,

    /** Override a product's price at the till. */
    PRICE_OVERRIDE,

    /** Apply a discount to an order or bill. */
    DISCOUNT_GRANT,

    /** Kassensturz — close a period on a non-fiscal cash book. */
    CASHBOOK_CLOSE,

    /**
     * Book a Kassenbuch entry whose Beleg date is older than the shop's limit
     * (`kassenbuch_max_beleg_age_days`, default 31), with a mandatory reason stored on the entry.
     *
     * A **handler gate**, like `ORDER_ITEM_CANCEL`: the route floor stays `CASHBOOK_WRITE`, and
     * `KassenbuchRoutes` asks for this only when the date is past the limit — a holder books
     * directly, anyone else who may post gets a 202 and a manager-approval request. Separate from
     * `CASHBOOK_WRITE` so a shop can let a bookkeeper post without letting them backdate.
     */
    CASHBOOK_BACKDATE,

    /**
     * Run a DATEV export (the accountant handoff).
     *
     * **Currently gates no route.** It is the capability `EXCEPTION_EXPORT_DATA` maps to, and that
     * grant is dead in the registry — no rule references it. It must therefore stay unattached: the
     * moment it gates a real route, every holder of the dead grant reaches that route. See
     * [DEBTOR_DATEV_EXPORT] for the concrete case where that bit.
     */
    DATEV_EXPORT,

    /**
     * The Debitoren (accounts-receivable) DATEV export — `/api/v1/sales-invoices/export/datev`.
     *
     * Separate from [DATEV_EXPORT] because these two routes are `Role(ADMIN)` while the rest of the
     * invoice family is manager-gated, and because [DATEV_EXPORT] is reachable through a legacy
     * grant that would have widened them to every grant holder.
     */
    DEBTOR_DATEV_EXPORT,

    /** Run a DSFinV-K export (the Kassenprüfung artefact). Deliberately separate from [DATEV_EXPORT]. */
    DSFINVK_EXPORT,

    /** Create a backup. Restoring is [BACKUP_RESTORE]. */
    BACKUP_CREATE,

    /** Reset another employee's password or PIN. */
    EMPLOYEE_CREDENTIAL_RESET,

    /** Revoke login sessions, including other employees'. */
    SESSION_REVOKE,

    /**
     * Manager override of a LIVE table session — force-closing it, transferring it to another table.
     *
     * Cannot be [TABLE_WRITE] (that sits at terminal level, since seating a customer is till work)
     * and is not floor-plan configuration, so it is its own operation capability.
     */
    TABLE_SESSION_OVERRIDE,

    /**
     * Repair orphaned completed table sessions — `POST /api/tables/repair-completed-sessions`.
     *
     * Its own capability rather than [SYSTEM_CONFIG]: that one sits at manager level (reached
     * through `EXCEPTION_SYSTEM_CONFIG`), while this route is `Role(ADMIN)`, so sharing would have
     * handed a data-repair tool to every manager.
     */
    TABLE_REPAIR,

    // ================================================================
    // Operation capabilities — RESERVED (system groups only)
    // ================================================================

    /** Close the day (Z-Bon). Produces the fiscal daily-close document. */
    ZBON_CLOSE(Assignability.RESERVED),

    /** Close an accounting month. Irreversible for the closed period. */
    MONTHLY_CLOSE(Assignability.RESERVED),

    /** Delete a document. GoBD retention makes this dangerous by nature. */
    DOCUMENT_DELETE(Assignability.RESERVED),

    /** Restore a backup — overwrites the live database and the documents directory. */
    BACKUP_RESTORE(Assignability.RESERVED),

    /** Modify system configuration. */
    SYSTEM_CONFIG(Assignability.RESERVED),

    /** Administer permission groups themselves. Whoever holds this can redefine access. */
    PERMISSION_ADMIN(Assignability.RESERVED),

    /** Administer the TSE: commissioning, PIN/PUK flows, self-test, deactivation. */
    TSE_ADMIN(Assignability.RESERVED),

    /** Administer the TSE signing queue (Nachsignieren). */
    TSE_QUEUE_ADMIN(Assignability.RESERVED),

    /**
     * Authorise a step-up: stand at a till, enter your PIN, and lend a colleague a capability they
     * lack for the next few minutes.
     *
     * Replaces the hardcoded `role.isManagerOrHigher()` the temporary-session service used. Seeded
     * into MANAGER and ADMIN, so behaviour is unchanged on the day it lands — but a purpose-built
     * shift-lead group can be given it later without a code change, and an ACCOUNTANT (who carries
     * role MANAGER and has nothing to do with the till) no longer gets it by accident.
     *
     * RESERVED, like the other operation capabilities: whoever holds it can hand out capabilities,
     * so no group edit may confer it. What it can lend is bounded twice over — by
     * `ElevationScope`, and by the approver's own set. You cannot lend what you do not hold.
     */
    ELEVATION_APPROVE(Assignability.RESERVED),
}
