package org.example.project.features.kassenbuch.data.service

/**
 * Work a server-internal booking must do INSIDE the Kassenbuch create transaction — after
 * `pg_advisory_xact_lock(1)` is held and before it commits — so that a guard and the booking it
 * guards cannot be separated by a concurrent writer.
 *
 * The employee-tip payout is the shape: "amount ≤ remaining" read outside the lock could let two
 * tills each pay the last 10 € of the same tip. Under the lock every Kassenbuch create (and the
 * Z-Bon closing) is serialized, so the re-check in [beforeCreate] sees every payout committed before
 * it, and [afterCreate]'s link row commits atomically with the entry or not at all.
 *
 * Both run on every AGGRESSIVE retry of the transaction, which is what makes them correct: a
 * rolled-back attempt left nothing behind.
 */
internal interface KassenbuchBookingHook {
    /** Re-validate under the lock; throw a `PosException` to refuse the booking (nothing commits). */
    fun beforeCreate()

    /** Record what links to the created entry, in the same transaction. */
    fun afterCreate(headerId: Long)
}
