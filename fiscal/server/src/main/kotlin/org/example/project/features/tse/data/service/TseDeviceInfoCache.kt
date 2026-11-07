/**
 * Process-level cache of TSE device properties that are expensive or awkward to read on the signing
 * hot path.
 *
 * @since 1.0.0
 */
package org.example.project.features.tse.data.service

import org.example.project.utils.formatter.TseSerialFormat

/**
 * m3: caches the TSE's internal log-time format (TSE_ZEITFORMAT, Anhang E p. 28).
 *
 * The format is a DEVICE CONSTANT surfaced only through pos-tse's `StatusOperation`
 * (`StatusInfoDto.logTimeFormat`) — there is no per-signature source, and the pos-tse boundary
 * forbids adding one. Rather than run a status build on the fiscal signing path, the admin status
 * path (which already runs `StatusOperation` for the UI/health) writes the value here, and the
 * signing persist reads it — a pure in-memory read with NO device I/O, so it can never destabilise
 * signing.
 *
 * When the cache is still empty (no status fetched this run), a newly signed row records null and
 * the DSFinV-K export falls back to `"unixTime"` — the correct value for the Swissbit device in use.
 * So the field converges to the true device format once status has been observed, and is never
 * wrong for the current hardware in the meantime.
 */
object TseDeviceInfoCache {
    @Volatile
    var logTimeFormat: String? = null
        private set

    /**
     * Records the device's log-time format. A blank/null observation is ignored so a transient
     * status read cannot erase a previously known-good value.
     */
    fun recordLogTimeFormat(format: String?) {
        if (!format.isNullOrBlank()) logTimeFormat = format
    }

    /**
     * Hex-normalized serial of the TSE this process last spoke to, or null before any device answer
     * this run.
     *
     * A retry finishes a STORED transaction number, and a transaction number means nothing without
     * the device that issued it: a replacement TSE restarts its counter at 1, so the stored number
     * is very likely OPEN on the new device — belonging to a DIFFERENT Vorgang. Finishing it would
     * write this Vorgang's process data into that Vorgang's transaction, permanently, in write-once
     * memory. [TseVorgangSigner] therefore requires this value to be KNOWN and to match the row's
     * serial before it finishes a resumed transaction; unknown is refused too, because a restart is
     * part of the very scenario the check exists for, so "nothing learned yet" is not evidence that
     * the same device is attached.
     *
     * Fed by every device answer the signer sees (start, finish, and so the signing path warms it
     * after its first Vorgang) AND — importantly — by `LocalTseAdminProvider.getInfo`, which the
     * status screen already calls. That is what keeps the ordinary restart resuming on the original
     * TANR instead of superseding it: the identity is learned without signing anything.
     *
     * The signer ALSO verifies the serial the device reports on the finish reply. That is not a
     * second prevention: by then the device has closed whatever it closed, and the check can only
     * stop a foreign signature being STORED against this row.
     */
    @Volatile
    var lastSeenSerialHex: String? = null
        private set

    /** Records the serial the device just reported, normalized to hex. Blank observations ignored. */
    fun recordSerial(serial: String?) {
        TseSerialFormat.normalizeToHex(serial)?.takeIf { it.isNotBlank() }?.let { lastSeenSerialHex = it }
    }

    /**
     * Test-only reset. The cache is a process-wide singleton, so a test that signs on one simulated
     * device would otherwise leave its serial behind for the next test's device-swap check.
     */
    fun forgetSerialForTests() {
        lastSeenSerialHex = null
    }
}
