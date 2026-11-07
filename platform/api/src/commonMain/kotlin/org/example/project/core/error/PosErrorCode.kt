package org.example.project.core.error

enum class PosErrorCode {
    VALIDATION_FAILED,
    AUTH_INVALID_CREDENTIALS,
    AUTH_SESSION_EXPIRED,
    AUTH_FORBIDDEN,

    /**
     * A manager step-up (temporary session) reached its hard cap — `createdAt` plus the server's
     * fixed maximum lifetime — and can no longer be extended. 409, not 401: the caller's own login
     * is fine; only the manager's approval ran out, and the manager must enter the PIN again.
     */
    AUTH_STEP_UP_CAP_REACHED,

    /**
     * The credentials were right but the employee has no permission group, so the session would hold
     * no capability at all and every call would 403. Refused at sign-in (403) with this code so the
     * client can say why, instead of starting a session that silently fails (and, on an empty
     * capability set, falls back to a role-shaped console).
     */
    NO_PERMISSION_GROUP,
    EMPLOYEE_NOT_FOUND,
    EMPLOYEE_USERNAME_TAKEN,
    TRANSACTION_LOG_NOT_FOUND,
    INTERNAL_ERROR,
    ALREADY_PROVISIONED,
    INVALID_REQUEST,
    UNPROVISIONED,
    SETUP_INCOMPLETE,
    MUST_CHANGE_PASSWORD,
    NO_PASSWORD,
    STANDALONE_MODE,
    ERP_MODE_ONLY,
    UNAVAILABLE_IN_MODE,
    DELETE_HAS_REFERENCES,
    SHOP_INFO_INVALID,
    TAX_CONFIG_MISSING,
    PRODUCT_NOT_FOUND,
    CATEGORY_NOT_FOUND,
    EXTRA_NOT_FOUND,
    CHOICE_GROUP_NOT_FOUND,
    CHOICE_SELECTION_INVALID,

    /**
     * An extra was deleted or deactivated while a required choice group still needs it: without it
     * the group would offer fewer options than its `minSelections`, and every product carrying the
     * group could no longer be ordered (admin21 NOTE-c21).
     *
     * **409 Conflict.** Lower the group's minimum, add another option, or remove the group first.
     */
    CHOICE_GROUP_OPTION_REQUIRED,

    /**
     * A product or extra price that is not a non-negative decimal number. **400.** Mapped to a
     * localized sentence on the client, instead of showing the server's English detail (admin21).
     */
    PRICE_INVALID,
    VAT_CODE_UNKNOWN,
    IMAGE_TOO_LARGE,

    /**
     * An uploaded file (a backup ZIP for restore) is larger than the configured upload cap.
     * **413 Payload Too Large**; the detail names the limit. Raise `maxUploadBytes` in the backup
     * configuration, or restore from a file already on the server instead.
     */
    UPLOAD_TOO_LARGE,
    IMAGE_TYPE_UNSUPPORTED,
    TABLE_NOT_FOUND,
    TABLE_HAS_NO_SESSION,
    TABLE_HAS_ACTIVE_SESSION,
    TABLE_NUMBER_ALREADY_TAKEN,
    TABLE_VIRTUAL_NOT_ORDERABLE,
    TRANSFER_TARGET_OCCUPIED_NOT_IMPLEMENTED,
    TRAINING_MODE_MISMATCH,
    LOCATION_TYPE_NOT_FOUND,
    LOCATION_TYPE_NAME_ALREADY_TAKEN,
    SESSION_ALREADY_CLOSED,
    EMPLOYEE_INACTIVE,
    EMPLOYEE_NUMBER_ALREADY_TAKEN,
    LAST_ADMIN,
    SELF_MODIFICATION_FORBIDDEN,
    PRINTER_NOT_FOUND,
    PRINTER_UNAVAILABLE,
    PRINTER_CONNECTION_INVALID,
    PRINT_SUBMISSION_FAILED,
    PRINT_JOB_NOT_FOUND,

    /**
     * A print was requested while printing is switched off in the shop configuration.
     *
     * Operator-fixable (Settings → Printers), so it is a 409 with a mapped, localized message —
     * not a 5xx, which the client escalates to a modal "unexpected error".
     */
    PRINTING_DISABLED,
    ORDER_NOT_FOUND,
    ORDER_ITEM_NOT_FOUND,
    ROTATION_POOL_EMPTY,
    STATION_TABLE_REQUIRES_ROTATION,
    STATION_TABLE_VIRTUAL,

    /**
     * An order station asked for a Storno approval. Owner decision 2026-10-03 (emu21 NOTE-v): a
     * station never queues a Storno request — a manager logs in temporarily on the station and
     * performs the Storno there. 403; nothing is queued.
     */
    STATION_STORNO_REQUIRES_MANAGER,
    ITEM_NOT_OPEN,
    ITEM_NOT_IN_SESSION,
    USE_TRANSFER_ENDPOINT,
    USE_TRANSFER_ITEMS_ENDPOINT,
    CUSTOM_PRICE_NOT_ALLOWED,
    DISCOUNT_NOT_ALLOWED,
    ORDER_HAS_NO_ITEMS,
    TSE_SIGNING_FAILED,
    TSE_NOT_CONFIGURED,

    /**
     * A TSE administrative operation was attempted without an unlocked admin session.
     *
     * The Admin PIN is not stored (Swissbit guidance manual §2.5), so the operator supplies it
     * once per sitting. Clients should prompt for it and retry.
     */
    TSE_ADMIN_LOCKED,
    TSE_DISABLED,
    TSE_UNREACHABLE,
    TSE_INVALID_CMD,

    // TSE — Connection (B1 amendment)
    TSE_HARDWARE_NOT_FOUND,
    TSE_CONNECTION_FAILED,
    TSE_COMMUNICATION_TIMEOUT,
    TSE_HARDWARE_BUSY,
    TSE_INVALID_MOUNT_POINT,

    // TSE — answers from the card (E2E R2-6, design 2026-09-22 §4.4). The card answered, so it
    // is connected: none of these may reset the connection.

    /** Not commissioned, decommissioned, client not registered, certificate expired, PIN/PUK change pending. */
    TSE_NOT_OPERATIONAL,

    /** The transaction number is not open on the device (WORM 0x1004 / 0x1008). */
    TSE_TRANSACTION_NOT_OPEN,

    /** Any other answer from the card rejecting the operation. */
    TSE_OPERATION_REJECTED,

    // TSE — Signing (B1 amendment)
    TSE_INVALID_PROCESS_TYPE,
    TSE_INVALID_PROCESS_DATA,
    TSE_TRANSACTION_START_FAILED,
    TSE_TRANSACTION_FINISH_FAILED,
    TSE_TRANSACTION_UPDATE_FAILED,
    TSE_AUTHENTICATION_FAILED,
    TSE_CERTIFICATE_ERROR,
    TSE_MAX_SIGNATURES_REACHED,

    // TSE — Admin (B1 amendment)
    TSE_SETUP_FAILED,
    TSE_ALREADY_INITIALIZED,

    /**
     * Setup has no terminal to register as the TSE's first client (404): none was chosen and the
     * calling device is not a terminal — the back office is a browser. Create a terminal first.
     */
    TSE_SETUP_TERMINAL_REQUIRED,

    /**
     * The TSE configuration has no TimeAdmin PIN (400, with a `timeAdminPin` field error). Every saved
     * configuration needs one — time sync runs unattended with it, and setup writes it to the device.
     * Enter it under "Zugangsdaten anzeigen" and save again.
     */
    TSE_TIME_ADMIN_PIN_REQUIRED,
    TSE_FACTORY_RESET_FAILED,
    TSE_SELF_TEST_FAILED,
    TSE_TIME_UPDATE_FAILED,
    TSE_CLIENT_REGISTRATION_FAILED,
    TSE_USER_UNBLOCK_FAILED,
    TSE_EXPORT_FAILED,

    /**
     * The TSE answered the TAR export, but neither it nor any registered, intact archive holds every
     * signed transaction the exported period references (checklist 28.4): the entries were deleted
     * from the TSE by an export-and-delete whose archive is not registered (or was altered), or the
     * attached device is not the one that signed the period — swapped, or a virtual TSE restarted
     * without its log. 409; the DSFinV-K export refuses rather than ship a TAR that verifies clean
     * while covering nothing.
     */
    TSE_EXPORT_INCOMPLETE,

    /** The TSE archive offered for registration is already in the archive register (same SHA-256). 409. */
    TSE_ARCHIVE_ALREADY_REGISTERED,
    TSE_DECOMMISSION_FAILED,
    TSE_INFO_RETRIEVAL_FAILED,
    TSE_PIN_CHANGE_FAILED,
    TSE_CTSS_OPERATION_FAILED,

    // TSE — Validation (B1 amendment)
    TSE_INVALID_PIN,
    TSE_INVALID_CLIENT_ID,
    TSE_INVALID_PARAMETERS,

    // TSE — Hardware (B1 amendment)
    TSE_DECOMMISSIONED,
    TSE_FIRMWARE_ERROR,
    TSE_INSUFFICIENT_CAPACITY,
    TSE_HARDWARE_MALFUNCTION,
    TSE_TIME_SYNC_REQUIRED,
    TSE_CERTIFICATE_EXPIRED,
    PAYMENT_TYPE_NOT_FOUND,
    PAYMENT_TYPE_INACTIVE,
    PAYMENT_TYPE_NAME_ALREADY_TAKEN,
    PAYMENT_AMOUNT_INVALID,
    CARD_TERMINAL_NOT_FOUND,
    CARD_TERMINAL_NAME_ALREADY_TAKEN,

    /**
     * A cash book with that name already exists in the shop (409). Compared trimmed and
     * case-insensitively, against active AND deactivated books — a deactivated book's name still
     * labels its historic entries and exports, and re-activating it would otherwise produce two
     * identically named ledgers.
     */
    CASH_BOOK_NAME_ALREADY_TAKEN,

    CARD_TERMINAL_DISCONNECTED,
    CARD_TERMINAL_UNREACHABLE,
    CARD_TERMINAL_BUSY,
    TERMINAL_NOT_FOUND,
    TERMINAL_NEEDS_PAIRING,
    TERMINAL_NOT_TSE_REGISTERED,

    /** Terminal-capability ceiling: no paired terminal resolves for the caller (409). */
    DEVICE_NOT_PAIRED,

    /** Terminal-capability ceiling: the terminal is deactivated (403). */
    TERMINAL_DEACTIVATED,

    /** Terminal-capability ceiling: a non-fiscal terminal attempted a signing route (403). */
    TERMINAL_NOT_FISCAL,

    /** The daily close is refused because a table still has open items (409). */
    ZBON_TABLES_OPEN,

    /**
     * The daily close is refused because an existing Z-Bon of the shop already ends after the
     * period the new one would start at (409). The stored closings are inconsistent (e.g. a
     * closing whose end time was moved); nothing was booked and no Z-number was consumed.
     */
    ZBON_PERIOD_OVERLAP,

    /**
     * A daily close was asked for when nothing is open to close: no sale, no Kassenbuch entry and no
     * tip payout since the last Z-Bon (400). Nothing was booked and no Z-number was consumed. It used
     * to answer a plain `VALIDATION_FAILED`, which the screens showed as an unexplained "unexpected error".
     */
    ZBON_NOTHING_TO_CLOSE,

    /**
     * An employee-tip payout asks for more than is still payable to that employee from that
     * closing — the tip total minus every payout already booked against it (409). Nothing was
     * booked; the response detail names the remaining amount.
     */
    ZBON_TIP_PAYOUT_EXCEEDS_REMAINING,

    /**
     * There is nothing to pay out: the employee has no employee tip in that closing, or the
     * closing predates employee-tip bookings and its tips cannot be paid out through the till
     * (409). Nothing was booked.
     */
    ZBON_TIP_PAYOUT_NOT_AVAILABLE,

    /** A tip-payout storno names a payout that was already reversed (409). Nothing was booked. */
    ZBON_TIP_PAYOUT_ALREADY_REVERSED,

    /**
     * A tip-payout storno names a Kassenbuch entry that is not a tip payout of that closing — or is
     * itself a storno (409). Nothing was booked.
     */
    ZBON_TIP_PAYOUT_NOT_A_PAYOUT,

    /**
     * The closing carries cash movements (count, cash tip payouts, top-up or the daily Geldtransit)
     * that must be TSE-signed, no signing till is designated on the Ladenkasse, and the caller is not
     * a registered till that could sign itself — e.g. the adminApp (409). Nothing was booked.
     */
    ZBON_SIGNER_NOT_DESIGNATED,

    /**
     * The till designated to sign the Ladenkasse's closing movements cannot sign: deactivated,
     * non-fiscal or without a registered TSE client (409). The detail names the till and the
     * reason. Nothing was booked.
     */
    ZBON_SIGNER_UNAVAILABLE,

    /**
     * The terminal cannot be designated as the Ladenkasse's signing till: the book is not the fiscal
     * one, or the terminal is from another shop, inactive, non-fiscal, not TSE-registered or not a
     * CASHIER till (409).
     */
    CASH_BOOK_SIGNER_INELIGIBLE,

    /**
     * Kassenminus guard: the booking would take a cash book below zero (409). Nothing was booked.
     *
     * "Available" is what the book physically holds at that moment: its running balance and, for
     * the fiscal Ladenkasse, the cash takings of bills no Z-Bon has closed yet — the same figure the
     * drawer count compares against (`DrawerCashDerivation`). The response detail names the
     * available amount. Stornos and the cash count's Kassendifferenz are exempt.
     */
    KASSENBUCH_INSUFFICIENT_CASH,
    PAIRING_CODE_INVALID,
    PAIRING_CODE_EXPIRED,

    /**
     * Pairing a fiscal terminal could not register its client with the TSE (424). Nothing was
     * bound and the code was not consumed; the device should ask an administrator to check the TSE
     * and, if the code has since lapsed, to issue a new one.
     */
    PAIRING_TSE_REGISTRATION_FAILED,

    /** A browser station of this role is not enabled for the shop (403). */
    BROWSER_STATION_DISABLED,
    CARD_TRANSACTION_DECLINED,
    CARD_TRANSACTION_ABORTED,
    CARD_PROTOCOL_ERROR,
    CARD_TRANSACTION_NOT_FOUND,
    CARD_SETTLEMENT_FAILED,
    BILL_NOT_FOUND,
    BILL_ALREADY_DELETED,
    BILL_AUTHORIZATION_DENIED,
    BILL_NUMBER_COLLISION,

    /**
     * Issuing or cancelling a sales invoice could not draw its §14 number because a concurrent
     * issuance held the year's counter (409). Nothing was committed; retrying the request is safe.
     */
    SALES_INVOICE_NUMBER_COLLISION,
    PAYMENT_TOTAL_MISMATCH,
    RESOURCE_NOT_FOUND,
    ROOM_ELEMENT_NOT_FOUND,
    SHOP_NOT_FOUND,
    VOUCHER_NOT_FOUND,
    ZBON_NOT_FOUND,
    TSE_TRANSACTION_NOT_FOUND,
    EMAIL_ALREADY_TAKEN,
    SHOP_NAME_ALREADY_TAKEN,
    VAT_CLASS_ALREADY_TAKEN,
    SERVICE_UNAVAILABLE,
    TSE_OPERATION_FAILED,
    SCHEDULED_JOB_CONFIG_MANAGED,

    /**
     * A fiscally relevant Stammdaten change was rejected because business is still open.
     *
     * DSFinV-K Tz. 3.2 (p.23): „Werden Änderungen an den … Stammdaten vorgenommen, ist zuvor
     * automatisch ein Abschluss zu erstellen." The operator must take a Kassenabschluss first, so
     * the turnover recorded so far stays attributed to the identity that applied to it.
     */
    SHOP_INFO_CLOSING_REQUIRED,

    /**
     * A supplier-asserted field was written on an invoice whose content came from the supplier's
     * own e-invoice XML.
     *
     * Neither a validation failure nor a permission problem: the payload is well-formed and a
     * MANAGER *is* allowed to edit invoices — this invoice's *state* forbids it, because replacing
     * XML-derived content with typed values loses fidelity that cannot be recovered. Thrown at
     * **409 Conflict**, matching [SCHEDULED_JOB_CONFIG_MANAGED], [BILL_ALREADY_DELETED] and
     * [ITEM_NOT_OPEN].
     *
     * Booking is unaffected — it lives in `invoice_booking` and is our decision, not the
     * supplier's.
     */
    INVOICE_SUPPLIER_CONTENT_READ_ONLY,

    /**
     * An incoming invoice cannot be cancelled by a correction (T9-19).
     *
     * **409 Conflict.** It is already cancelled (a second correction would neutralise it twice), it
     * is itself a correction, it has no Beleg to hang the correction on, or it is already allocated
     * or marked paid — a paid invoice needs its Zuordnung undone first, otherwise the payment would
     * stay consumed by an invoice that no longer exists.
     */
    INVOICE_NOT_CANCELLABLE,

    /**
     * A write to a cancelled incoming invoice or to the correction that cancelled it (T9-19).
     *
     * **409 Conflict.** Both halves of a Storno are frozen: editing the original's totals or number
     * would detach it from the counter-entry that neutralises it.
     */
    INVOICE_CANCELLED_READ_ONLY,

    /**
     * A creditor with this VAT ID already exists.
     *
     * `supplier.vat_id` is unique because EN 16931 BT-31 is an exact identity — that uniqueness is
     * what makes the parse path dedupe suppliers at all. A manager typing in a creditor the parser
     * has already auto-created is the routine way to hit it, so it is a **409 Conflict** with a
     * usable code rather than the bare 500 an unhandled unique-violation would produce.
     */
    SUPPLIER_VAT_ID_TAKEN,

    /**
     * One of our own bank accounts already carries this IBAN.
     *
     * `bank_account.iban` is unique because that uniqueness is what makes statement → account
     * resolution total. The seeder creates the shop's account from `shop_info` on start, so a
     * manager typing in the account the seeder already made is the routine way to hit it — a **409
     * Conflict** with a usable code rather than the bare 500 an unhandled unique-violation would
     * produce. Spacing and case do not matter: the IBAN is canonicalised before comparison.
     */
    BANK_ACCOUNT_IBAN_TAKEN,

    /**
     * An allocation would assign more than the payment has left.
     *
     * Thrown at **409 Conflict**, not 400: the request is well-formed and the caller is permitted —
     * the *state* of the transaction forbids it. `residualOf` deliberately lets a residual go
     * negative so a mistake that reached the database another way stays visible; this code stops
     * the endpoint from creating that mistake in the first place.
     */
    ALLOCATION_EXCEEDS_SOURCE,

    /**
     * A voucher operation was attempted while the voucher feature is switched off.
     *
     * `VOUCHER_CONFIG.enabled` gates the whole subsystem: turning it on is what seeds the voucher
     * category, the `VOU` product and the `GUTSCHEIN` payment type. Without that, the sale path used
     * to fall through to its product lookup and report "VOUCHER product not found" as a **500**,
     * which describes the symptom rather than the fixable cause. **409 Conflict**: the request is
     * well-formed and the caller permitted - the shop's *configuration* forbids it.
     */
    VOUCHER_FEATURE_DISABLED,

    /**
     * An order line names the `VOUCHER` product (`VOU`). A voucher is sold through the voucher sale
     * (`POST /api/vouchers`), which creates the voucher record that gives the line its value; ordered
     * directly, the product booked a 0,00 sale with no voucher behind it (QA MD-10). **400**.
     */
    VOUCHER_PRODUCT_NOT_ORDERABLE,

    /**
     * An allocation was attempted against a transaction whose statement has no `bank_account_id`.
     *
     * Which internal account a statement belongs to is our decision and may not have been made yet.
     * Until it is, the transaction has no shop, so no guard that depends on one can be evaluated —
     * which is why such rows never enter the queue either. Also **409**.
     */
    ALLOCATION_SOURCE_UNRESOLVED,

    /**
     * An allocation was attempted against an outgoing invoice that is not `ISSUED`.
     *
     * **409 Conflict.** A DRAFT has no obligation yet and a CANCELLED one no longer exists — writing
     * `open_amount` onto either would make it report itself partially paid and consume the payment's
     * residual against something that is not a debt. Only an issued invoice is a target; the
     * Zuordnung picker already lists only those, this guard keeps a direct API call honest.
     */
    ALLOCATION_TARGET_NOT_OPEN,

    /**
     * A cash-book entry was backdated into a period that is already closed.
     *
     * `KassenbuchRoutes` has documented `409 The target period is already closed` on
     * `POST /api/v1/kassenbuch` since it was written — and route KDoc is the source of the
     * generated OpenAPI spec, so the published API advertised the guard while nothing implemented
     * it. `belegDatum` is a free varchar validated for format only, so `01.01.1999` was accepted
     * into an already-exported period.
     *
     * **409 Conflict**: the request is well-formed and the caller is permitted — the *period's*
     * state forbids it. The route's documented status, kept. Corrections are deliberately exempt;
     * a Storno of an entry inside a closed period must stay possible.
     *
     * **No longer thrown (2026-09-21).** The booking time decides an entry's period, so a late
     * receipt keeps its date and books into the open period; a bad Beleg date is now a 400
     * `VALIDATION_FAILED` from `KassenbuchBelegDateRule` (future, or older than the shop's limit).
     * Kept so a client can still decode it from an older server.
     */
    KASSENBUCH_PERIOD_CLOSED,

    /**
     * A DATEV Buchungsstapel export was requested before the shop configured its account mapping.
     *
     * Without a mapping, `KassenbuchAccountResolver` yields `Legacy` for every line and the
     * exporter falls back to the per-entry-type default accounts.
     * Those defaults are not merely approximate — they are wrong in three ways that an accountant
     * cannot see from the file:
     *
     * - the cash-flow sign is applied to `Konto`, which for `CASH_EXPENSE`, `PRIVATE_WITHDRAWAL`,
     *   `REFUND`, `CASH_SURPLUS` and `CASH_SHORTAGE` holds a non-Kasse account, so both legs of the
     *   booking are reversed (cash *rises* on a cash purchase);
     * - every VAT rate is booked to the 19 % automatic account with no BU-Schlüssel, so
     *   reduced-rate turnover is taxed at the standard rate;
     * - cashless tenders take the revenue account as `Gegenkonto`, subtracting card and voucher
     *   takings from declared turnover instead of moving them to a transit account.
     *
     * Thrown at **409 Conflict**, not 400: the request is well-formed and the caller is permitted —
     * the shop's *configuration state* forbids it. Refusing is strictly safer than exporting,
     * because a silently mis-booked Buchungsstapel is discovered by the Steuerberater, or not at
     * all. The mapping screen pre-fills SKR03/SKR04 defaults, so clearing this is a save away.
     *
     * **One throw site is NOT a configuration gap, despite this code.** A
     * `RECEIVABLE_SETTLEMENT` line carrying no payment type
     * (`KassenbuchBuchungsstapelRowMapper.unresolvableLine`) is refused because the tender that
     * *opened* the receivable could not be determined — the settled invoice has no linked original
     * bill, that bill has no `createsReceivable` leg, or its receivable legs name more than one
     * tender. There is no "unknown tender" cell in `mapping.paymentTypes` and none can be created,
     * so saving the mapping screen will **not** clear it; the remedy is to look at the invoice and
     * the receipt it was raised from. That branch says so in its own message. The code is shared
     * because the HTTP contract is identical (409, export refused) and a dedicated code would have
     * to be registered here and mapped client-side for no behavioural gain — but do not read this
     * code as "the mapping is incomplete" without checking which message came with it.
     */
    DATEV_ACCOUNT_MAPPING_REQUIRED,

    /**
     * A DATEV catch-up (preview or run) was requested for an export type that has no schedule:
     * `BUCHUNGSSTAPEL_MIT_BELEGEN` is ad-hoc only (the ZIP with receipts is built on request, never
     * on a cadence), and `AR_LEDGER` is not available yet. **400**, refused before any period is
     * enumerated — it used to answer 200 with the refusal repeated as a per-period failure (23.5).
     */
    DATEV_EXPORT_TYPE_NOT_SCHEDULABLE,

    /**
     * A restored backup carried filesystem paths (DATEV export folder, backup folder, document
     * storage) that point outside this installation's app-data directory — typically the original
     * shop's folders when the backup was restored onto another machine or another install. Until an
     * administrator has reviewed them (`GET/POST /api/v1/backup/restored-paths`: keep each path or
     * switch it to this installation's default), every file write to such a path is paused: scheduled
     * DATEV exports and backups skip, and an operator-requested export or server-side backup that
     * would write there is refused. **409 Conflict** — the request is valid, the installation's
     * state forbids it until the review is done.
     */
    RESTORED_PATHS_NEED_REVIEW,

    /**
     * An outgoing invoice was edited after it was issued.
     *
     * **409 Conflict**: the request is well-formed and the caller is permitted — the *invoice's*
     * state forbids it. §14 UStG makes an issued invoice a fixed document; the only legal change is
     * a 381 Gutschrift, which is a new document with its own number.
     */
    SALES_INVOICE_IMMUTABLE,

    /**
     * An outgoing invoice could not be issued because its content is incomplete or inconsistent —
     * no buyer, no lines, no Leistungszeitpunkt, or totals that do not add up.
     *
     * **422 Unprocessable Entity**, and deliberately checked *before* a number is consumed: a
     * rejected issuance must leave the sequence untouched.
     */
    SALES_INVOICE_NOT_ISSUABLE,

    /**
     * The bill an invoice was to be rendered from cannot be invoiced — it is a training bill, has no
     * items or VAT details, or its own VAT details disagree with its total. A bill that is not a
     * standing sale at all (Storno, cancelled original, clearing) is refused with
     * [BILL_NOT_A_STANDING_SALE] instead.
     *
     * **409 Conflict.** Issuing anyway would produce a §14 document asserting a sale the fiscal
     * record does not support.
     */
    SALES_INVOICE_BILL_NOT_INVOICEABLE,

    /**
     * The action needs a standing sale, and this bill is something else: a Storno bill (including the
     * Storno half of a Zahlartwechsel), a cancelled original or a receivable clearing (a payment
     * receipt). Refused for creating or issuing an Ausgangsrechnung from the bill and for printing a
     * Bewirtungsbeleg of it.
     *
     * **409 Conflict.** Only a standing sale documents a supply: an ordinary sale or the Neubuchung of
     * a Zahlartwechsel (same items, corrected tender) stands, a Storno and its cancelled original no
     * longer do, and a clearing settles an invoice rather than selling anything.
     */
    BILL_NOT_A_STANDING_SALE,

    /**
     * This bill cannot be rebooked by a Zahlartwechsel: it sells a gift voucher or clears an
     * invoice. Storno it and bill the table again instead.
     *
     * **409 Conflict.**
     */
    BILL_REBOOK_UNSUPPORTED,

    /**
     * The Zahlartwechsel changes neither the tender, the vouchers nor the tip.
     *
     * **409 Conflict.**
     */
    BILL_REBOOK_NO_CHANGE,

    /**
     * A promotion on this bill was edited since the sale, so booking it again would change the
     * discount. Storno it and bill the table again instead.
     *
     * **409 Conflict.**
     */
    BILL_REBOOK_PROMOTION_CHANGED,

    /**
     * The Zahlartwechsel would lower the bill's tip, and the caller does not hold `BILL_VOID`. A
     * lower tip takes back part of what was booked — an owner tip is taxable revenue — which only
     * someone who may storno the bill outright may do. Keeping or raising the tip needs only
     * `BILL_CHANGE_PAYMENT`.
     *
     * **403 Forbidden.**
     */
    BILL_REBOOK_TIP_DECREASE_FORBIDDEN,

    /**
     * The bill already has a live invoice.
     *
     * **409 Conflict.** A guest holding both a Kassenbeleg and an invoice for the same meal can
     * deduct both, so exactly one live invoice per bill is a hard rule. Cancelling the existing one
     * frees the slot.
     */
    SALES_INVOICE_BILL_ALREADY_INVOICED,

    /**
     * The invoice total does not equal the bill it claims to render.
     *
     * **409 Conflict.** A receipt and an invoice for the same sale that disagree — even by a cent —
     * is precisely what a Betriebsprüfer looks for.
     */
    SALES_INVOICE_AMOUNT_MISMATCH,

    /**
     * The bill carries an OWNER tip, and its stored VAT details do not split into "meal" plus the
     * owner-tip share this version computes. The Ausgangsrechnung covers the meal and never the tip,
     * so the tip must come back out of each VAT row to the cent; when it cannot (typically a bill from
     * before the tip was apportioned over the item rates), no invoice is made rather than a wrong one.
     *
     * **409 Conflict.**
     */
    SALES_INVOICE_OWNER_TIP_NOT_SEPARABLE,

    /**
     * The bill carries a promotion (Aktion), and its stored promotions do not reproduce its VAT
     * details exactly — a deduction on an item no longer on the bill, deductions that do not add up
     * to the bill's promotion amount, or a VAT row that differs from its items less their
     * promotions. Each promotion appears on the Ausgangsrechnung as an EN 16931 allowance (BG-20)
     * that must agree with the Kassenbeleg to the cent; when it cannot, no invoice is made.
     *
     * **409 Conflict.**
     */
    SALES_INVOICE_BILL_NOT_RECONCILABLE,

    /**
     * Part of the bill's tip — employee or owner — was paid "auf Rechnung": the bill's receivable
     * tender exceeds the meal total. The Ausgangsrechnung covers the meal only, so it cannot ask for
     * the tip, and the guest would owe money no invoice names. Refused on create and on issue; the
     * operator rebooks the tip to cash or card (Zahlartwechsel) and then creates the invoice.
     *
     * **409 Conflict.**
     */
    SALES_INVOICE_TIP_ON_ACCOUNT,

    /**
     * A cancellation was attempted on an invoice that is not `ISSUED`.
     *
     * **409 Conflict.** A draft is deleted, not cancelled — it consumed no number, so there is
     * nothing to reverse. An already-cancelled invoice has its Gutschrift already.
     */
    SALES_INVOICE_NOT_CANCELLABLE,

    /**
     * The standalone editor was used on an invoice whose revenue origin is not `STANDALONE`.
     *
     * **409 Conflict.** A `POS_SETTLED` draft is a snapshot of a fiscalised bill — its lines, VAT
     * breakdown and totals are the receipt's, and the standalone editor would replace them with
     * typed ones. An issued §14 invoice for an arbitrary amount carrying the TSE signature block of
     * a receipt for a different amount is exactly what the fiscal rules forbid, so the rewrite is
     * refused before it can start. Only a case-C invoice may be edited through this path.
     */
    SALES_INVOICE_ORIGIN_MISMATCH,

    /**
     * The configured Ausgangsrechnung prefix collides with the Kassenbeleg prefix.
     *
     * **500 Internal Server Error**, because it is a misconfiguration rather than a bad request.
     * Two ranges that can emit the same string is the one failure here with no recovery: once two
     * documents share an identifier, nothing can say which one a payment or an audit refers to.
     */
    SALES_INVOICE_PREFIX_COLLISION,

    /**
     * The tender offered to clear a receivable would itself create one.
     *
     * **409 Conflict.** Settling a receivable by opening another is not a payment; paying by
     * transfer is the bank route, which needs no till action at all.
     */
    SALES_INVOICE_TENDER_NOT_SETTLING,

    /**
     * The amount offered exceeds what the invoice still owes.
     *
     * **409 Conflict.** An overpayment at the till is a different act — the answer is change or a
     * credit note, not a silently negative receivable.
     */
    SALES_INVOICE_OVERPAYMENT,

    /**
     * A debtor opted into ZUGFeRD, but the invoice does not carry every field a valid EN 16931
     * e-invoice needs (a missing seller/buyer identifier, no buyer address, an inconsistent VAT
     * total, …).
     *
     * **422 Unprocessable Entity**, and deliberately raised *before* the §14 number is consumed:
     * shipping a plain PDF where the debtor was promised a machine-readable e-invoice is a silent
     * compliance failure, so the issue is refused rather than downgraded. The message names the
     * offending field so the operator can fix the invoice and re-issue; because nothing was
     * consumed, the refusal leaves no gap in the number sequence.
     */
    SALES_INVOICE_EINVOICE_INVALID,

    /**
     * The DATEV Debitoren export was blocked because at least one STANDALONE invoice in the period
     * has an account that does not resolve — no Personenkonto (no booking value, no debtor number,
     * no Sammeldebitor) or an unmapped revenue account for one of its VAT rates.
     *
     * **409 Conflict.** The export refuses to run rather than silently omit the offending invoices:
     * an outgoing §14 UStG document missing from the accountant's books is worse than a blocked
     * export. The message names the invoice numbers so the operator can fix their accounts and retry.
     */
    SALES_INVOICE_EXPORT_BLOCKED,

    /**
     * Another debtor already carries that Personenkonto.
     *
     * **409 Conflict.** A released number is retired, never reused: reassigning 10247 to a second
     * customer merges two debtors' history in the tax advisor's books.
     */
    DEBTOR_NUMBER_TAKEN,

    /**
     * Another debtor already carries that VAT id.
     *
     * **409 Conflict.** Spacing and case are ignored when comparing, so `DE123456789` and
     * `de 123 456 789` collide.
     */
    DEBTOR_VAT_ID_TAKEN,

    /**
     * A delete was attempted on a debtor an invoice references.
     *
     * **409 Conflict.** The invoice is kept ten years under §147 AO and must keep pointing at the
     * party it was issued to, so such a debtor is deactivated rather than removed.
     */
    DEBTOR_IN_USE,

    /**
     * A new sales invoice was created for, or a draft issued to, a deactivated debtor.
     *
     * **409 Conflict.** Deactivating is how a debtor that invoices still reference is retired
     * (see [DEBTOR_IN_USE]); billing it again would undo that decision behind the operator's back.
     * Reactivate the debtor first. Cancelling an already-issued invoice is not affected.
     */
    DEBTOR_INACTIVE,

    /**
     * A bill offered a promotion flagged "not combinable with other promotions" together with at
     * least one other, distinct promotion.
     *
     * **409 Conflict**: the request is well-formed and the caller is permitted — the *promotion's*
     * own terms forbid the combination. The message names the offending promotion so the cashier
     * knows which one to drop. Applying the same non-combinable promotion several times on one
     * bill is `isMultiUse`, a different axis, and is not affected by this code.
     */
    PROMOTION_NOT_COMBINABLE,

    /**
     * A release artifact could not be fetched from GitHub.
     *
     * **502 Bad Gateway.** The request reached GitHub (or its object storage) but the answer was
     * unusable -- a redirect with no `Location`, too many hops, or a non-success status on the
     * download. Distinct from a plain network outage, which never becomes a `PosException`: the
     * nightly check records that as an unreachable outcome and retries.
     */
    UPDATE_DOWNLOAD_FAILED,

    /**
     * A downloaded release artifact did not match its published SHA-256 digest, or the digest
     * could not be established at all.
     *
     * **502 Bad Gateway.** The bytes arrived but cannot be trusted: the `SHA256SUMS` body names
     * no server archive, names it with an unusable digest, or the digest disagrees with what was
     * downloaded. Staging refuses to put such an artifact on disk, so there is nothing to clean
     * up and nothing an operator could accidentally install.
     */
    UPDATE_CHECKSUM_MISMATCH,

    /**
     * An update was abandoned before anything on disk was replaced.
     *
     * **500 Internal Server Error.** The staged archive carried no usable `pos-server.jar`, or the
     * mandatory pre-apply database backup was not written or came out empty. The applier refuses
     * to swap the running binary in any of those cases: a jar rollback cannot undo a Flyway
     * migration, so without a restorable backup a failed new build would be an investigation
     * rather than a restore. The server keeps running the binary it already has.
     */
    UPDATE_APPLY_ABORTED,

    /**
     * A manual apply was refused because the pre-install preflight is blocking.
     *
     * **409 Conflict.** The request is well-formed and the caller is permitted; the *shop* is not
     * ready — open business, an open table session, unfinished TSE work, another job running,
     * or not enough free disk. The detail names which one, because an operator
     * standing at the till at 03:00 needs to know it was open bills, not "the update failed".
     */
    UPDATE_BLOCKED,

    /**
     * A manual apply was requested with no verified artifact on disk.
     *
     * **409 Conflict.** Nothing has been staged, or the recorded staging is gone from disk. Run
     * the check and the stage first; this is never a reason to install unverified bytes.
     */
    UPDATE_NOT_STAGED,

    /**
     * A token was submitted on a host with no `UPDATE_TOKEN_KEY` provisioned.
     *
     * **409 Conflict.** Storing it would seal it with nothing, so the write is refused outright
     * rather than writing a value that could never be read back. The operator provisions the key
     * in the systemd EnvironmentFile and retries.
     */
    UPDATE_TOKEN_KEY_MISSING,

    /** The issue-report token or repository is not configured. */
    ISSUE_REPORT_NOT_CONFIGURED,

    /** GitHub could not be reached, or answered with a non-success status the report cannot recover from. */
    ISSUE_REPORT_GITHUB_UNREACHABLE,

    /** GitHub rejected the stored token (revoked, expired, or lacking Issues: write on this repository). */
    ISSUE_REPORT_TOKEN_REJECTED,

    /** Too many reports in the last hour (this server's own cap) or GitHub's secondary rate limit. */
    ISSUE_REPORT_RATE_LIMITED,

    /**
     * HTTP 400 on saving the problem-report settings: "use the update token" is on, but the
     * problem-report API address is not the update source's. The update token may only ever be sent
     * to the host the update feature is configured for; the remedy is to set the same API address,
     * or to use a dedicated problem-report token instead.
     *
     * Save-time only. The same divergence found at *send* time (the update source moved after the
     * settings were saved) stays [ISSUE_REPORT_NOT_CONFIGURED].
     */
    ISSUE_REPORT_UPDATE_TOKEN_HOST_MISMATCH,

    /**
     * HTTP 409 on sending a problem report: the Administrator chose to file reports with the update
     * token, but no update token is stored, or it cannot be read on this host. The remedy is under
     * Settings → Updates, not Settings → Problem reports, which is why it is not
     * [ISSUE_REPORT_NOT_CONFIGURED] (that one stays for a missing or unreadable *own* token).
     */
    ISSUE_REPORT_UPDATE_TOKEN_UNAVAILABLE,

    /**
     * A merge was asked for between two parties that each already carry a creditor role.
     *
     * **409 Conflict.** `supplier` carries `UNIQUE (partner_id)` — one creditor per company — so the
     * two roles cannot both survive. Which one does decides the creditor number the DATEV export
     * writes and which invoices stay attached, a judgement with fiscal reach, so the merge is
     * refused rather than guessed and the operator settles the creditors first.
     */
    PARTNER_MERGE_CREDITOR_CONFLICT,

    /**
     * A merge was asked for between two parties that each already carry a debtor role.
     *
     * **409 Conflict.** `debtor.partner_id` is unique, so the two roles cannot both survive — and
     * which debtor number stays is the same kind of choice as for creditors. It used to reach the
     * database and fail as a 500 duplicate-key error (E2E round 4, R4-11).
     */
    PARTNER_MERGE_DEBTOR_CONFLICT,

    /**
     * The maintainer login was refused because of too many consecutive failures.
     *
     * **429 Too Many Requests**, with a `Retry-After` header. The delay starts at 30 s and doubles up
     * to 15 min; a successful login resets it.
     */
    MAINTENANCE_LOGIN_THROTTLED,

    /**
     * The maintainer log search cannot read the server's log directory.
     *
     * **503 Service Unavailable.** The directory is missing or unreadable; nothing the caller sent
     * is wrong.
     */
    MAINTENANCE_LOG_DIR_UNAVAILABLE,

    /**
     * An unlock was requested for an uploaded PDF (the `unlockPassword` field was sent) with an
     * **empty** password, but the PDF needs one.
     *
     * **422 Unprocessable Entity.** Nothing was stored. An absent `unlockPassword` field never
     * raises this: that is "store as-is", and the server does not reject a locked PDF on its own.
     */
    PDF_PASSWORD_REQUIRED,

    /**
     * An unlock was requested for an uploaded PDF with a non-empty password that does not open it.
     *
     * **422 Unprocessable Entity.** Nothing was stored; the operator may try again.
     */
    PDF_PASSWORD_INCORRECT,

    /**
     * An update or delete targeted a record the system itself depends on: the PFAND or VOUCHER
     * product category, the voucher article, the virtual VOUCHER table or the virtual
     * Gutschein/Schnellverkauf location types (`SystemRecords`). Renaming, deactivating or deleting
     * one would break deposits, voucher sales or historical quick-sale bills.
     *
     * **409 Conflict.** The client hides the edit and delete controls for these records; the server
     * refuses a direct API call. Nothing was changed.
     */
    SYSTEM_RECORD_PROTECTED,
}
