package org.example.project.core.domain.dto.terminal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Payload encoded in a back-office pairing QR (spec §4.4): the server base URL the
 * device should connect to plus the single-use pairing code. Scanning it fills both
 * the "connect to server" step and the pairing code in one action.
 *
 * Single shared definition: the RB2 device-side scanner [parse]s it; the RB3 back-office
 * issuer and the RB4 first-run flow [encode] it.
 */
@Serializable
data class PairingQrPayload(
    val serverUrl: String,
    val code: String,
    /**
     * Prüfcode of the server's root certificate (`RootFingerprint`), present when the server runs
     * behind Caddy's local CA. A till that reads it pins that root before it sends [code]; an older
     * app ignores the field and trusts the system store only.
     */
    val rootFp: String? = null,
) {
    /** JSON-encodes this payload for embedding in a QR (`{"serverUrl":"...","code":"...","rootFp":"..."}`, `rootFp` omitted when absent). */
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        /** Parses a scanned QR string; returns null unless it is a valid pairing payload with both fields non-blank. */
        fun parse(raw: String): PairingQrPayload? =
            runCatching { json.decodeFromString<PairingQrPayload>(raw) }
                .getOrNull()
                ?.takeIf { it.serverUrl.isNotBlank() && it.code.isNotBlank() }
    }
}
