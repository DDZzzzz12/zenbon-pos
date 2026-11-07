package org.example.project.features.issuereport.data.service

import org.example.project.features.issuereport.domain.models.ReportSnapshot
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * The previews a send may refer to, so what is posted is exactly what the operator read.
 *
 * In memory (a restart forgets every preview; the operator previews again), thread-safe, bounded to
 * [maxEntries] (the oldest is evicted) and expiring after [ttl]. A snapshot is single use: [take]
 * removes it, and only the employee who created it can take it — a foreign or unknown id answers
 * null without disturbing anything. [restore] puts a taken snapshot back after a send that did not
 * create an issue, keeping its original age, so a retry posts the same text without a new preview.
 *
 * Ids are 128 random bits from [SecureRandom], hex-encoded: a guessed id is never someone's preview.
 */
class PreviewCache(
    private val ttl: Duration = DEFAULT_TTL,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Instant = { Instant.now() },
    private val random: SecureRandom = SecureRandom(),
) {
    /** Insertion order is age order, so the first entry is always the oldest. */
    private val entries = LinkedHashMap<String, ReportSnapshot>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Stores [snapshot] and returns its fresh, unguessable id. */
    @Synchronized
    fun put(snapshot: ReportSnapshot): String {
        purgeExpired()
        val id = newId()
        entries[id] = snapshot
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return id
    }

    /** The snapshot for [previewId] if it exists, is fresh and belongs to [employeeId]; removes it. */
    @Synchronized
    fun take(
        previewId: String,
        employeeId: String,
    ): ReportSnapshot? {
        purgeExpired()
        val snapshot = entries[previewId] ?: return null
        if (snapshot.employeeId != employeeId) return null
        entries.remove(previewId)
        return snapshot
    }

    /** Returns a [take]n snapshot under its old id, unless it has expired meanwhile. */
    @Synchronized
    fun restore(
        previewId: String,
        snapshot: ReportSnapshot,
    ) {
        if (isExpired(snapshot)) return
        entries[previewId] = snapshot
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return entries.size
    }

    private fun purgeExpired() {
        entries.entries.removeIf { isExpired(it.value) }
    }

    private fun isExpired(snapshot: ReportSnapshot): Boolean = !snapshot.createdAt.plus(ttl).isAfter(clock())

    private fun newId(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20
        val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
        private const val ID_BYTES = 16
    }
}
