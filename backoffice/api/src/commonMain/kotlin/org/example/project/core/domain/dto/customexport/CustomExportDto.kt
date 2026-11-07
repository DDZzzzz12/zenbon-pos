package org.example.project.core.domain.dto.customexport

import kotlinx.serialization.Serializable

/**
 * Wire contract for the custom export feature: the catalogue a client renders, and the definitions an
 * operator saves against it.
 *
 * Two decisions run through the whole container:
 *
 * - **Keys, not ordinals.** [FieldDto.type] is the name of the server's `ExportFieldType`, not its
 *   position in the enum, so reordering the enum cannot silently change how a client renders a
 *   column. The same reasoning makes every reference to a subject, field or toggle a string key.
 * - **Dates are ISO strings.** `2026-09-01T00:00` crosses the wire as text, never as an epoch number,
 *   for the same reason money does: a number is a format nobody can read in a log and every client
 *   parses slightly differently.
 *
 * No field here is money. If one is ever added it is a `String` — see `platform/money/README.md`.
 */
object CustomExportDto {
    /** One column an operator may tick. */
    @Serializable
    data class FieldDto(
        val key: String,
        val label: String,
        val type: String,
        /**
         * The picker's section heading, or null for a column of the subject's own table.
         *
         * Additive and defaulted, so an adminApp build predating this slice renders the flat list it
         * always did rather than failing to decode.
         */
        val group: String? = null,
        /**
         * Whether this column is a summary over a to-many relation rather than a plain column.
         *
         * Published as its own flag, and **not** derivable by the client from the shape of [key]: the
         * `~` an aggregate key carries is a server-side encoding, and a client that sniffed for it
         * would break silently the day that encoding changed.
         *
         * The picker needs it because aggregates are the capped kind — see
         * [SubjectDto.maxAggregateColumns]. Additive and defaulted, so an older adminApp build
         * decodes the catalogue unchanged and simply gates nothing.
         */
        val isAggregate: Boolean = false,
    )

    /** One named, pre-written filter an operator may switch on. */
    @Serializable
    data class ToggleDto(
        val key: String,
        val label: String,
    )

    /**
     * One thing an operator can export a table of, as *this caller* may see it.
     *
     * [fields] is already filtered to what the caller's capabilities cover, so a client renders what
     * it is given. That filtering is a convenience, not the control: the server checks again on save.
     */
    @Serializable
    data class SubjectDto(
        val key: String,
        val label: String,
        val fields: List<FieldDto>,
        val toggles: List<ToggleDto>,
        /** Whether the subject has a column the date range can apply to. */
        val hasDateFilter: Boolean,
        /**
         * The most [FieldDto.isAggregate] columns one definition may select.
         *
         * A **service-wide** limit, repeated on every subject for the client's convenience: the
         * picker is rendered per subject and would otherwise need a second, subject-less catalogue
         * call to learn one number. Published rather than duplicated as a client constant so the
         * picker gates on the server's value and the two cannot drift.
         *
         * Additive and defaulted to the server's own production value, so an older adminApp build
         * decodes the catalogue unchanged.
         */
        val maxAggregateColumns: Int = 5,
    )

    /** One chosen column of a saved export: which field, where in the row, and under which heading. */
    @Serializable
    data class DefinitionFieldDto(
        val fieldKey: String,
        val position: Int,
        val heading: String,
    )

    /** A saved export, as the list and detail screens render it. */
    @Serializable
    data class DefinitionDto(
        val id: Long,
        val name: String,
        val subjectKey: String,
        /** Always in [DefinitionFieldDto.position] order — the server sorts, the client does not. */
        val fields: List<DefinitionFieldDto>,
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val enabledToggles: List<String> = emptyList(),
        /** True when the catalogue no longer knows this definition's subject or one of its fields. */
        val isBroken: Boolean = false,
        /** The keys the catalogue could not resolve. Empty unless [isBroken]. */
        val unknownKeys: List<String> = emptyList(),
    )

    /**
     * A finished run: the file itself, plus what the operator needs to know about it.
     *
     * Carries the raw [bytes] rather than Base64 or a path, matching `DatevStammdatenExportDto.ResultDto`
     * so a route handler streams every export the same way. Not `@Serializable` for the same reason it
     * is not there: a [ByteArray] belongs in the response body, not inside a JSON envelope. The two
     * counts that do not fit in a body — [rowCount] and [truncated] — travel as response headers.
     *
     * [truncated] is true when the query matched more rows than the runner will write. It is not an
     * error: a partial file is still useful, but an operator who is not told is an operator who
     * reconciles against a silently short list.
     */
    data class RunResultDto(
        val fileName: String,
        val bytes: ByteArray,
        /** Data rows written, excluding the heading row. */
        val rowCount: Int,
        val truncated: Boolean,
    ) {
        // Hand-written because a data class compares a ByteArray by identity, which would make two
        // byte-identical exports unequal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RunResultDto) return false
            return fileName == other.fileName &&
                bytes.contentEquals(other.bytes) &&
                rowCount == other.rowCount &&
                truncated == other.truncated
        }

        override fun hashCode(): Int {
            var result = fileName.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + rowCount
            result = 31 * result + truncated.hashCode()
            return result
        }
    }

    /**
     * A new definition.
     *
     * There is no `isBroken` here, and there cannot be: brokenness is a fact about the catalogue at
     * read time, not something a client asserts. A request naming a key the catalogue does not know
     * is rejected outright — there is no reason to persist a key we already know is wrong.
     */
    @Serializable
    data class SaveDefinitionRequestDto(
        val name: String,
        val subjectKey: String,
        val fields: List<DefinitionFieldDto>,
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val enabledToggles: List<String> = emptyList(),
    )
}
