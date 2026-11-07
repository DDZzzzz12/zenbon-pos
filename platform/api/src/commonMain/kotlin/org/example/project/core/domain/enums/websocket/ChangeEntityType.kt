package org.example.project.core.domain.enums.websocket

/**
 * Unified entity-type taxonomy for the change feed (entry_log) AND client sync.
 *
 * Supersedes the former SyncEntityType + LogType split. Every mutation logs one
 * of these; [clientSynced] = true means the client maintains a local copy and
 * should act on change-feed entries of this type.
 *
 * ## Reading spellings this build no longer writes
 *
 * `entry_log` is an append-only audit trail that outlives any rename of this enum, so a stored
 * `type` string is NOT guaranteed to be a name declared below. Two independent mechanisms keep a
 * stale row readable, because they protect against different things:
 *
 * 1. **[current] = false entries retain a spelling that was once written.** Six exist (see below).
 *    They are read back with their true historical label, so the change-log screen still says what
 *    actually happened. This is the house pattern for a value that must survive in old rows but
 *    must never be written again — `QUICK_SALE`, and `OCR_LLM` on `InvoiceTable`. It fixes the
 *    spellings we know about; it does nothing for the next rename.
 * 2. **[fromStored] resolves anything else to [UNKNOWN] instead of throwing.** That is the durable
 *    half: `EntryLogTable` reads the column through it, so a spelling introduced by a *future*
 *    rename degrades to one odd label on one row rather than an exception that fails the whole
 *    query. Losing a row is worse than mislabelling it — this is a change log, and its job is
 *    completeness.
 *
 * Without (2) a single unreadable row took down the entire Änderungen screen: Exposed's
 * `EnumerationNameColumnType.valueFromDB` throws on an unmatched string, and the screen's query has
 * no type filter, so it reads every row.
 *
 * ### Why the retained spellings exist
 *
 * `V80__changefeed_entrylog_type_rename.sql` (folded into `V77__release_core_features.sql`) rewrote
 * five of them in place. That migration is not a guarantee: a restore reloads the archive's rows
 * *after* migrations have run, so a backup taken before V80 reintroduces every one of them. The
 * sixth, `PRODUCT_CATEGORY`, no migration ever rewrote — it was this enum's own spelling for five
 * weeks and was renamed to [CATEGORY] with no accompanying SQL.
 *
 * ### Why every retained entry is `clientSynced = false`
 *
 * Not because the entity was not synced — [PRODUCTEXTRAS] rows are product-extra changes — but to
 * preserve today's behaviour exactly. `EntryLogRepositoryImpl` selects the client change feed with
 * `type inList` the `clientSynced` values; a legacy string matches no constant today, so such rows
 * are already absent from the feed. Marking them synced would newly replay months-old changes to
 * every connected client and move `maxClientSyncedSeq`. Retaining them is a **read** fix; it must
 * not become a feed change.
 */
enum class ChangeEntityType(
    val clientSynced: Boolean,
    /**
     * Whether this build still writes this value.
     *
     * `false` marks a spelling kept only so historical rows stay readable. Callers that offer the
     * taxonomy as a choice — the audit screen's type filter — must list only `current` values;
     * offering a retired spelling next to the one that replaced it is a choice with nothing behind
     * it, and offering [UNKNOWN] invites filtering for a value nothing is deliberately stored as.
     */
    val current: Boolean = true,
) {
    // Shared (logged + client-synced)
    PRODUCT(true),
    CATEGORY(true),
    PRODUCT_EXTRA(true),
    PRODUCT_EXTRAS_ASSIGNMENT(true),
    CHOICE_GROUP(true),
    PRODUCT_CHOICE_GROUP(true),
    PAYMENT_TYPE(true),
    RTABLE(true),
    ROOM_ELEMENT(true),
    SHOP_INFO(true),
    SHOP_CONFIGURATION(true),
    OPENING_HOURS(true),

    // Audit-only (logged, not client-synced)
    EMPLOYEE(false),
    VAT(false),
    SETUP(false),
    TERMINAL(false),
    PRINTER_LOCATION(false),
    TIME_TRACKING(false),

    // ---------------------------------------------------------------------------------------
    // Retired spellings — READ ONLY. Never write one; log the replacement named in each comment.
    // ---------------------------------------------------------------------------------------

    /** Pre-V80 spelling of [SHOP_INFO]. */
    SHOP(false, current = false),

    /** Pre-V80 spelling of [PRODUCT_EXTRA]. */
    PRODUCTEXTRAS(false, current = false),

    /** Pre-V80 spelling of [PAYMENT_TYPE]. */
    PAYMENTTYPE(false, current = false),

    /** Pre-V80 spelling of [ROOM_ELEMENT]. */
    ROOMELEMENT(false, current = false),

    /** Pre-V80 spelling of [PRINTER_LOCATION]. */
    PRINTERLOCATION(false, current = false),

    /**
     * This enum's own spelling of [CATEGORY] between 2026-06-23 and 2026-08-01.
     *
     * The only retired spelling with **no** migration behind it: V80 rewrites the other five but
     * carries `UPDATE entry_log SET type = 'CATEGORY' WHERE type = 'CATEGORY'`, a no-op where the
     * `PRODUCT_CATEGORY` source value belonged. Any install written by a build from that window
     * still holds these rows, migrated or not.
     */
    PRODUCT_CATEGORY(false, current = false),

    /**
     * A stored value no name above matches — the fallback [fromStored] returns.
     *
     * Never stored. It exists so a rename this build cannot anticipate costs one wrong label
     * instead of the whole query.
     */
    UNKNOWN(false, current = false),
    ;

    companion object {
        /**
         * The value [stored] denotes, or [UNKNOWN] when this build does not know it.
         *
         * Deliberately total: this is the read path for an audit trail, where refusing a row is
         * strictly worse than describing it imprecisely. Use [valueOf] where the input is a *query*
         * (a filter the caller chose from a list we published) and a wrong value should be
         * rejected — only a value read back out of storage gets the fallback.
         */
        fun fromStored(stored: String): ChangeEntityType = entries.firstOrNull { it.name == stored } ?: UNKNOWN
    }
}
