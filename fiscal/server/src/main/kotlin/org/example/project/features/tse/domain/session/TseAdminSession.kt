package org.example.project.features.tse.domain.session

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the operator-supplied TSE Admin PIN for the duration of an admin sitting.
 *
 * The Swissbit guidance manual (§2.5) forbids the ERS from *storing* the Admin PIN or PUK
 * while explicitly permitting the TimeAdmin PIN, so this deliberately lives in memory only:
 * never written to the database, never serialised into a DTO, never logged, and gone when the
 * process ends.
 *
 * Nothing routine needs it. Transaction signing needs no credential at all, the scheduled
 * self test needs no login, and time sync runs on TimeAdmin — so an expired session only ever
 * costs an operator one prompt before a deliberate administrative action.
 *
 * The idle window is shorter than the TSE 2.0 device-side 40-minute logout, so this session
 * never outlives the device session it is used with.
 */
object TseAdminSession {
    const val IDLE_TIMEOUT_MS: Long = 15 * 60 * 1000

    private class Held(
        val pin: String,
        val lastUsedAtMs: Long,
    )

    private val held = AtomicReference<Held?>(null)

    // Monotonic sitting id. Changes on every unlock() so a code bound to an older sitting is
    // invalidated once a new sitting starts. Deliberately NOT stored in Held — pinOrNull()
    // reconstructs Held on every read to slide the idle window, and must never bump the epoch.
    private val epoch = AtomicLong(0)

    /** Starts an admin sitting with an operator-supplied PIN. */
    fun unlock(
        pin: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        epoch.incrementAndGet()
        held.set(Held(pin, nowMs))
    }

    /**
     * The PIN if a session is live, else null.
     *
     * Reading extends the idle window, so an operator working through several administrative
     * actions is not re-prompted mid-task.
     */
    fun pinOrNull(nowMs: Long = System.currentTimeMillis()): String? {
        val current = held.get() ?: return null
        if (nowMs - current.lastUsedAtMs > IDLE_TIMEOUT_MS) {
            held.compareAndSet(current, null)
            return null
        }
        held.compareAndSet(current, Held(current.pin, nowMs))
        return current.pin
    }

    /**
     * Whether a sitting is live, WITHOUT extending it.
     *
     * Status reads (`GET /api/tse/admin/status`, the back office polling it) and the
     * [TseAdminSessionReaper]'s 60-second poll call this. Had it slid the idle window like
     * [pinOrNull], the reaper alone would keep every sitting alive forever (T9-7). Only a real
     * administrative action — one that takes the PIN — counts as use.
     */
    fun isUnlocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = held.get() ?: return false
        if (nowMs - current.lastUsedAtMs > IDLE_TIMEOUT_MS) {
            held.compareAndSet(current, null)
            return false
        }
        return true
    }

    /** The current sitting epoch if a session is live, else null. Reading the pin slides the window (as before). */
    fun currentEpochIfUnlocked(nowMs: Long = System.currentTimeMillis()): Long? = if (pinOrNull(nowMs) != null) epoch.get() else null

    /** Ends the sitting. Called on explicit lock, and safe to call when already locked. */
    fun clear() = held.set(null)

    /** Never renders the PIN — this object can reach logs and stack traces. */
    override fun toString(): String = "TseAdminSession(unlocked=${held.get() != null})"
}
