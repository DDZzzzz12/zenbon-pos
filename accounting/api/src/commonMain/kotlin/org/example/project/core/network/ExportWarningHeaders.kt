package org.example.project.core.network

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.datev.UnassignedSummary

/**
 * How a file download carries the notices its export produced.
 *
 * A file body has nowhere to put them, so they travel as response headers: a count, and the
 * notices themselves as percent-encoded UTF-8, one per line (header values must be ASCII, and the
 * notices contain dates, umlauts and dashes). Server and client share this object so neither side
 * can drift from the other's encoding. E2E round 4, R4-4: the Buchungsstapel period warnings were
 * computed and then dropped at the HTTP layer, so no operator ever saw them.
 */
object ExportWarningHeaders {
    const val COUNT = "X-Export-Warning-Count"
    const val TEXT = "X-Export-Warnings"

    fun encode(warnings: List<String>): String = warnings.joinToString("\n").encodeURLParameter()

    fun decode(header: String?): List<String> =
        header
            ?.takeIf { it.isNotBlank() }
            ?.decodeURLQueryComponent(plusIsSpace = true)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * The lines exported without a contra account because their mapping cell's „Konto automatisch
     * zuordnen" switch is off. Structured (percent-encoded JSON) rather than a sentence in [TEXT], so
     * the client can name the bookings in the operator's language.
     */
    const val UNASSIGNED = "X-Export-Unassigned-Contra"

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeUnassigned(summary: UnassignedSummary): String = json.encodeToString(UnassignedSummary.serializer(), summary).encodeURLParameter()

    /** Null when the header is absent or unreadable — a notice must never fail the download it rides on. */
    fun decodeUnassigned(header: String?): UnassignedSummary? =
        header
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    json.decodeFromString(
                        UnassignedSummary.serializer(),
                        it.decodeURLQueryComponent(plusIsSpace = true),
                    )
                }.getOrNull()
            }
}
