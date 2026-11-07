package org.example.project.core.domain.enums.setup

/**
 * What an `entry_log` row records having been done.
 *
 * These four names have never been renamed, so — unlike
 * `org.example.project.core.domain.enums.websocket.ChangeEntityType` — no historical spelling needs
 * retaining. The exposure is still identical in kind: the column is read back into this enum, and
 * an unmatched string throws out of Exposed's column type and fails the whole change-log query, not
 * just the offending row. [fromStored] closes that.
 */
enum class LogOperation {
    CREATE,
    UPDATE,
    DELETE,
    ERROR,
    ;

    companion object {
        /**
         * The operation [stored] denotes, or [ERROR] when this build does not know it.
         *
         * [ERROR] rather than a new `UNKNOWN` constant, for two reasons:
         *
         * - **It is the conservative value.** `EntryLogRepositoryImpl` builds the client change feed
         *   with `operation neq ERROR`, so an unreadable row stays out of the feed instead of being
         *   pushed to every till as a change none of them can act on. A new constant would default
         *   to the opposite, and would also put an unknown name on the `ChangeFeedDto` wire, where
         *   an older client deserializing this enum would fail on it.
         * - **It says the right thing.** A row whose operation this build cannot read *is* an
         *   error condition, and the audit screen already renders [ERROR] distinctly — the row
         *   surfaces as something to look at rather than disappearing.
         *
         * The cost is honest and accepted: such a row's true operation is not shown. It is only
         * reachable by a future rename of one of the four names above, and showing the row with the
         * wrong verb beats dropping every row on the screen.
         */
        fun fromStored(stored: String): LogOperation = entries.firstOrNull { it.name == stored } ?: ERROR
    }
}
