package org.example.project.core.auth

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * Role and legacy-grant → [Capability] mapping, hardcoded for Stage 1 only.
 *
 * **In `shared/`, not `pos-server/`, on purpose.** The server's route plugin and the client's
 * `RoleBackedAccessPolicy` must resolve through the *same* definition; two copies would drift and
 * CLAUDE.md rule 8 would fail by construction rather than by accident.
 *
 * The sets are **derived, never designed**: a capability belongs in a role's bundle if and only if
 * the pre-migration registry let that role reach every route now requiring it. They start empty and
 * are filled module by module in Stage 1 Task 6, each addition proven by `CapabilityEquivalenceTest`.
 *
 * **Stage 3 re-keyed this object on the group CODE, not the role.** `ACCOUNTANT`, `AUDITOR` and
 * `ORDER_ONLY` are groups with no corresponding `EmployeeRole`, and both seed paths used to iterate
 * `EmployeeRole.entries` — so a non-role group had nowhere to live. [forRole] survives as a thin
 * alias because the route plugin's device-token path still needs it. This is the parent spec's
 * "`EmployeeRole` reduced to a seed alias", arriving a stage early because Stage 3 needed it.
 *
 * **Stage 2a moved the authority to the `permission_group` table.** What survives here is:
 *  - the source the V110 seed and `SchemaManager`'s H2 seeding are both generated from,
 *  - the shadow comparator the route plugin logs against for one release after cutover,
 *  - the backing for the client's `RoleBackedAccessPolicy` until track C2 lands.
 *
 * `forGrant` and `resolve` are gone: the `EXCEPTION_*` mechanism they served was retired in Stage 2a
 * because it was not merely unused but unusable — no client populated it and no endpoint managed it.
 */
object CapabilityBundles {
    /**
     * One seeded group: its identity, the row's display metadata, and what it confers.
     *
     * The metadata lives here rather than only in the migration because `SchemaManager` seeds H2
     * from this same object, and two hand-kept copies of a group's name is exactly the drift
     * `SeededGroupsMatchBundlesTest` exists to prevent.
     */
    data class GroupDefinition(
        val code: String,
        val name: String,
        val description: String,
        val capabilities: Set<Capability>,
    )

    /**
     * What an unattended terminal device holds — and, since every other bundle builds on it, the
     * floor for every principal.
     *
     * Stage 1 derived this from the routes the legacy registry marked `Authenticated`, which is why
     * it started out holding shop-wide reporting: defect D4. **Stage 2b trims it.** Four capabilities
     * left, and each one moved *up into [CASHIER]* rather than being deleted — so the reach of every
     * human role is byte-identical to Stage 1 and exactly one principal, the device, loses anything.
     * Confining the blast radius that way is what makes the change reviewable; tightening what a
     * cashier may see is Stage 3's job, alongside the `ACCOUNTANT`/`AUDITOR` groups.
     *
     * The four, and why the till does not need them:
     *  - `REPORT_READ`, `DOCUMENT_READ` — `PosNavDestination` already hides the Reports and Financial
     *    Documents screens from terminals (`minRole = MANAGER`), so no reachable flow breaks.
     *  - `VOUCHER_REPORT` — new in 2b; shop-wide voucher statistics and the by-date rollups. The till
     *    keeps `VOUCHER_READ`, which is validate plus one voucher's redemption history.
     *  - `CUSTOMER_LIST` — new in 2b; enumerating the whole customer base. The till keeps
     *    `CUSTOMER_READ` (search, by id) and `CUSTOMER_WRITE`, which the table-session
     *    create-customer dialog needs.
     *
     * `TerminalBundleTrimTest` pins the resulting route-level diff so a later edit cannot widen the
     * device's reach again by accident.
     */
    private val TERMINAL_DEVICE: Set<Capability> =
        setOf(
            Capability.BILL_READ,
            Capability.CARD_TERMINAL_READ,
            Capability.CATALOG_READ,
            Capability.CHANGELOG_READ,
            Capability.CONFIG_READ,
            Capability.CUSTOMER_READ,
            Capability.CUSTOMER_WRITE,
            Capability.NOTIFICATION_READ,
            Capability.ORDER_READ,
            Capability.ORDER_WRITE,
            Capability.PAYROLL_READ,
            Capability.PRINTER_READ,
            Capability.PRINTER_WRITE,
            Capability.PROMOTION_READ,
            Capability.RESERVATION_READ,
            Capability.RESERVATION_WRITE,
            Capability.SELF_READ,
            Capability.SELF_WRITE,
            Capability.SHOP_INFO_READ,
            Capability.SYNC_READ,
            Capability.TABLE_READ,
            Capability.TABLE_WRITE,
            Capability.TERMINAL_READ,
            Capability.TERMINAL_SELF_REGISTER,
            Capability.VOUCHER_READ,
            Capability.VOUCHER_WRITE,
            Capability.ZBON_READ,
        )

    /**
     * Adds what a legacy `AnyRole(... CASHIER ...)` rule allowed, plus the two of the four Stage 2b
     * moved down out of [TERMINAL_DEVICE] that a till genuinely needs (`REPORT_READ`, `CUSTOMER_LIST`).
     *
     * `DOCUMENT_READ` and `VOUCHER_REPORT` were moved here by 2b only to keep every human role's reach
     * byte-identical, and left for "Stage 3" to tighten. Trimmed here (V130, product decision D1): no
     * cashier/till/terminal flow calls a route gated by either — the till validates and creates
     * vouchers (`VOUCHER_READ`/`VOUCHER_WRITE`) and its document viewers render in-memory bytes — so
     * they now start at [MANAGER], which re-adds them.
     */
    private val CASHIER: Set<Capability> =
        TERMINAL_DEVICE +
            setOf(
                Capability.BILL_CHANGE_PAYMENT,
                Capability.BILL_WRITE,
                Capability.CUSTOMER_LIST,
                Capability.REPORT_READ,
            )

    /**
     * Adds what a legacy `Role(MANAGER)` rule allowed, plus the two Stage 3 gave a real meaning.
     *
     * `PRICE_OVERRIDE` and `DISCOUNT_GRANT` gate no route — they gate `OrderServiceImpl`'s custom-
     * price and steep-discount checks, which until Stage 3 asked `role.isManagerOrHigher()` directly.
     * Seeding them into MANAGER and nowhere else keeps **exactly the same people** able to do it, so
     * the swap is not a narrowing. What it buys is separability: a group can now grant discounting
     * without price override, or either without the rest of a manager's reach.
     */
    private val MANAGER: Set<Capability> =
        CASHIER +
            setOf(
                Capability.ACCOUNTING_READ,
                Capability.ACCOUNTING_WRITE,
                Capability.AUDIT_READ,
                Capability.BILL_VOID,
                Capability.CARD_TERMINAL_OPERATE,
                Capability.CASHBOOK_BACKDATE,
                Capability.CASHBOOK_CLOSE,
                Capability.CASHBOOK_READ,
                Capability.CASHBOOK_WRITE,
                Capability.CATALOG_MANAGE,
                Capability.CONFIG_WRITE,
                Capability.CONTRACT_READ,
                Capability.CONTRACT_WRITE,
                Capability.CREDITOR_READ,
                Capability.CREDITOR_WRITE,
                // The operator who defines a shop's exports, and runs the ones already defined.
                // Admission to the feature only — the catalogue still filters every field against the
                // rest of this bundle, so a manager builds and runs exports of exactly the data they
                // could already read.
                Capability.CUSTOM_EXPORT_MANAGE,
                Capability.CUSTOM_EXPORT_READ,
                Capability.DEBTOR_READ,
                Capability.DEBTOR_WRITE,
                Capability.DISCOUNT_GRANT,
                Capability.DOCUMENT_DELETE,
                // Moved up from CASHIER (V130): shop-wide document and voucher reporting is back-office.
                Capability.DOCUMENT_READ,
                Capability.DOCUMENT_WRITE,
                Capability.DSFINVK_EXPORT,
                Capability.ELEVATION_APPROVE,
                Capability.EMPLOYEE_CREDENTIAL_RESET,
                Capability.EMPLOYEE_READ,
                Capability.EMPLOYEE_WRITE,
                Capability.INVOICE_READ,
                Capability.INVOICE_WRITE,
                // Filing a problem report; the token and target repository (ISSUE_REPORT_MANAGE)
                // stay ADMIN-only.
                Capability.ISSUE_REPORT_CREATE,
                Capability.KASSEN_MELDUNG_READ,
                Capability.MONTHLY_CLOSE,
                Capability.ORDER_ITEM_CANCEL,
                Capability.PRICE_OVERRIDE,
                Capability.PROMOTION_MANAGE,
                Capability.REPORT_ALL_READ,
                Capability.RESERVATION_DELETE,
                Capability.SCHEDULED_JOB_READ,
                Capability.SCHEDULED_JOB_WRITE,
                Capability.SESSION_READ,
                Capability.SESSION_WRITE,
                Capability.SETUP_READ,
                Capability.SYSTEM_CONFIG,
                Capability.TABLE_SESSION_OVERRIDE,
                Capability.TERMINAL_MANAGE,
                Capability.TIMETRACKING_DATEV_EXPORT,
                Capability.TIMETRACKING_READ,
                Capability.TIMETRACKING_WRITE,
                Capability.TRAINING_REPORT_READ,
                Capability.VOUCHER_REPORT,
                // Read only. A manager should see that a release is waiting -- it is their shop
                // that will restart -- but installing one swaps the binary and migrates the live
                // fiscal database, which is administrator work. UPDATE_MANAGE is ADMIN-only.
                Capability.UPDATE_READ,
                Capability.ZBON_CLOSE,
                Capability.ZBON_MANAGE,
            )

    /**
     * Total by construction — the bundle form of the plugin's "ADMIN always passes" short-circuit.
     *
     * Expressing it as a bundle rather than a code branch is what makes admin's reach auditable in
     * Stage 5's `effective` endpoint instead of invisible.
     */
    private val ADMIN: Set<Capability> = Capability.entries.toSet()

    /**
     * A Betriebsprüfer's account: **provably** read-only.
     *
     * The exact membership matters less than the property, which `AuditorIsReadOnlyTest` derives
     * from `RoutePermissions` rather than restating: every rule gated by a capability in this set
     * must be a GET. A hand-curated list rots quietly; a derived assertion fails the moment someone
     * puts a write route behind a capability the auditor holds.
     *
     * `SELF_WRITE` is deliberately absent, so an auditor cannot even change their own PIN — an admin
     * sets it. That cost is accepted: "this group can write nothing" is the group's whole purpose and
     * is worth being able to prove, and audit accounts are short-lived.
     */
    private val AUDITOR: Set<Capability> =
        setOf(
            Capability.ACCOUNTING_READ,
            Capability.AUDIT_READ,
            Capability.BILL_READ,
            Capability.CASHBOOK_READ,
            Capability.CATALOG_READ,
            Capability.CHANGELOG_READ,
            Capability.CONFIG_READ,
            Capability.CONTRACT_READ,
            Capability.CREDITOR_READ,
            Capability.CUSTOMER_LIST,
            Capability.CUSTOMER_READ,
            // Run an extract someone else prepared. The read tier gates three routes and all three
            // are GETs, so the group's derived read-only property still holds — which is the only
            // reason this can be added at all.
            //
            // It confers no new data access. Both export subjects draw on creditors and debtors, and
            // the auditor already holds CREDITOR_READ and DEBTOR_READ — the exact capabilities that
            // gate every field in them — alongside DOCUMENT_READ, PAYROLL_READ, REPORT_ALL_READ and
            // DSFINVK_EXPORT. The runner then re-checks each field against the *runner's* own
            // capabilities and refuses the run rather than blanking a column, so an auditor can only
            // export what they could already read on the screen.
            //
            // The original exclusion was right when it was written: only CUSTOM_EXPORT_MANAGE existed
            // then, it was destined to gate writes, and this group must hold none. CUSTOM_EXPORT_READ
            // arrived later for an unrelated reason and inherited the exclusion without anyone
            // re-reading it. CUSTOM_EXPORT_MANAGE stays out — an auditor runs an extract, they do not
            // design one.
            Capability.CUSTOM_EXPORT_READ,
            Capability.DEBTOR_READ,
            Capability.DOCUMENT_READ,
            Capability.DSFINVK_EXPORT,
            Capability.EMPLOYEE_READ,
            Capability.INVOICE_READ,
            Capability.KASSEN_MELDUNG_READ,
            Capability.ORDER_READ,
            Capability.PAYROLL_READ,
            Capability.REPORT_ALL_READ,
            Capability.REPORT_READ,
            Capability.SELF_READ,
            Capability.SHOP_INFO_READ,
            Capability.TABLE_READ,
            Capability.TERMINAL_READ,
            Capability.TIMETRACKING_ALL_READ,
            Capability.TIMETRACKING_READ,
            Capability.TRAINING_REPORT_READ,
            Capability.VOUCHER_REPORT,
            Capability.VOUCHER_READ,
            // Both halves of the Z-Bon split. ZBON_READ alone lists the daily closes but cannot open
            // one: `/recent`, `/{id}`, `/history` and `/{id}/pdf` all require ZBON_MANAGE. An auditor
            // who can see that Z-Bons exist and not read them is useless — the daily close is close
            // to the first document an audit asks for.
            //
            // Added 2026-08-23 (V113), found by driving adminApp's accountant sidebar against the
            // real API: the Z-Bon screen was hidden, and it should not have been. Stage 3 defined
            // these groups from the capability list; the gap only showed once something used them.
            Capability.ZBON_MANAGE,
            Capability.ZBON_READ,
        )

    /**
     * A bookkeeper: the auditor's reads plus the writes that booking actually needs.
     *
     * **Everything that makes a period final is excluded on purpose.** `ZBON_CLOSE`,
     * `MONTHLY_CLOSE`, `CASHBOOK_CLOSE` and `DOCUMENT_DELETE` are all `Assignability.RESERVED`:
     * fiscal finality is the operator's button, not the bookkeeper's.
     * `AccountantHoldsNoReservedCapabilityTest` keeps that true as the set is edited.
     *
     * Also absent: `EMPLOYEE_WRITE`, `CATALOG_MANAGE`, `CONFIG_WRITE`. An accountant books; they do
     * not run the shop.
     */
    private val ACCOUNTANT: Set<Capability> =
        AUDITOR +
            setOf(
                Capability.ACCOUNTING_WRITE,
                Capability.CASHBOOK_WRITE,
                Capability.CONTRACT_WRITE,
                Capability.CREDITOR_WRITE,
                // The accountant is who asks for a column the standard exports do not have; handing
                // them the builder is the point of the feature, and the read tier is how they run the
                // monthly extract afterwards.
                //
                // Only the manage tier is kept out of [AUDITOR]: its routes are writes, and that
                // group's whole property is that it holds none. The read tier is *in* [AUDITOR] and
                // so reaches this set through the union above — see there for why running an export
                // grants an auditor no reach they did not already have.
                Capability.CUSTOM_EXPORT_MANAGE,
                Capability.DATEV_EXPORT,
                Capability.DEBTOR_DATEV_EXPORT,
                Capability.DEBTOR_WRITE,
                Capability.DOCUMENT_WRITE,
                Capability.INVOICE_WRITE,
                Capability.NOTIFICATION_READ,
                Capability.SELF_WRITE,
                Capability.TIMETRACKING_DATEV_EXPORT,
            )

    /**
     * A till employee who takes and views orders, and does nothing else.
     *
     * A **strict subset of [TERMINAL_DEVICE]** (asserted), dropping bills, the card terminal,
     * payroll, reservations, vouchers, the Z-Bon list and station self-registration.
     *
     * What it keeps is what order-taking actually touches: tables, because orders are taken against
     * a table session; customers, because that flow attaches one; printers, because an order goes to
     * the kitchen; and `PROMOTION_READ`, because an order's pricing depends on active promotions.
     *
     * Named for what it permits. `TILL_ONLY` would imply general till operation, and this group
     * cannot take payment.
     */
    private val ORDER_ONLY: Set<Capability> =
        setOf(
            Capability.CATALOG_READ,
            Capability.CHANGELOG_READ,
            Capability.CONFIG_READ,
            Capability.CUSTOMER_READ,
            Capability.CUSTOMER_WRITE,
            Capability.NOTIFICATION_READ,
            Capability.ORDER_READ,
            Capability.ORDER_WRITE,
            Capability.PRINTER_READ,
            Capability.PRINTER_WRITE,
            Capability.PROMOTION_READ,
            Capability.SELF_READ,
            Capability.SELF_WRITE,
            Capability.SHOP_INFO_READ,
            Capability.SYNC_READ,
            Capability.TABLE_READ,
            Capability.TABLE_WRITE,
            Capability.TERMINAL_READ,
        )

    /**
     * Every seeded group, keyed by code — the single source both seed paths and the V110/V112
     * migrations are generated from.
     *
     * The four role groups keep `EmployeeRole`'s own display metadata so nothing about them changes;
     * the Stage 3 groups carry their own, since they have no role to borrow it from.
     */
    private val DEFINITIONS: List<GroupDefinition> =
        EmployeeRole.entries.map { role ->
            GroupDefinition(role.name, role.displayName, role.description, forRole(role))
        } +
            listOf(
                GroupDefinition(
                    "ACCOUNTANT",
                    "Accountant",
                    "Books invoices, documents and the cash ledger, and runs the DATEV exports. " +
                        "Cannot close a period, delete a document, or change catalog, staff or system settings.",
                    ACCOUNTANT,
                ),
                GroupDefinition(
                    "AUDITOR",
                    "Auditor",
                    "Read-only access for an external audit, including the DSFinV-K export. " +
                        "Writes nothing at all — not even their own PIN.",
                    AUDITOR,
                ),
                GroupDefinition(
                    "ORDER_ONLY",
                    "Order Taking Only",
                    "Takes and views orders at a table, and nothing else. No bills, payments, " +
                        "vouchers, reservations or reports.",
                    ORDER_ONLY,
                ),
            )

    /** Every seeded group definition, in seed order. */
    fun definitions(): List<GroupDefinition> = DEFINITIONS

    /** What the group with this code confers, or null if no such group is seeded. */
    fun forGroup(code: String): Set<Capability>? = DEFINITIONS.firstOrNull { it.code == code }?.capabilities

    /**
     * The capability set a role's own group confers.
     *
     * Still needed after the Stage 3 re-keying: the route plugin resolves a login-less device token
     * this way (it has no employee row, so no group), and the cutover shadow comparator compares
     * against it.
     */
    fun forRole(role: EmployeeRole): Set<Capability> =
        when (role) {
            EmployeeRole.TERMINAL_DEVICE -> TERMINAL_DEVICE
            EmployeeRole.CASHIER -> CASHIER
            EmployeeRole.MANAGER -> MANAGER
            EmployeeRole.ADMIN -> ADMIN
        }
}
