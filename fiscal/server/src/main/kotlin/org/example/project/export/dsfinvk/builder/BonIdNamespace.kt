package org.example.project.export.dsfinvk.builder

/**
 * Single source of truth for DSFinV-K `BON_ID` namespace prefixes (audit finding C5).
 *
 * Bills, Orders, Cancellations and Kassenbuch entries live in four separate `LongIdTable`s,
 * each with its own auto-increment sequence starting at 1. Emitting a raw PK as `BON_ID` would
 * collide across sources (Bill#1 vs Order#1 vs Cancellation#1 vs Kassenbuch#1), making every
 * parent/child join in the export ambiguous. Each source therefore gets a distinct one/two-letter
 * prefix.
 *
 * The prefix strings are defined ONLY here so the "all source prefixes are mutually distinct"
 * invariant is enforced in one place: a future dev adding a 5th source picks a new letter here and
 * immediately sees the existing four.
 */
object BonIdNamespace {
    /** Namespaced BON_ID for a Bill (transactions.csv Beleg + all its child rows). */
    fun bill(id: Long): String = "B-$id"

    /** Namespaced BON_ID for an Order (AVBestellung header + its child rows). */
    fun order(id: Long): String = "O-$id"

    /**
     * Namespaced BON_ID for a cancellation BATCH — the rows created by one cancel action.
     *
     * Cancelling N items is ONE Vorgang carrying N Bonpos lines, exactly as an order of N items is
     * one `AVBestellung` Vorgang, and it is covered by ONE TSE signature. Keying the Vorgang on the
     * batch rather than on each per-item `Cancellation` row is what makes that signature verifiable
     * against the Beleg (M2).
     *
     * [key] is `Cancellation.batchId`, or a `legacy-*` key derived for rows written before that
     * column existed — never a raw row id, so it cannot collide with the other sources' sequences.
     */
    fun cancellation(key: String): String = "C-$key"

    /** Namespaced BON_ID for a Kassenbuch entry (AVSonstige header + its child rows). */
    fun kassenbuch(id: Long): String = "KB-$id"
}
