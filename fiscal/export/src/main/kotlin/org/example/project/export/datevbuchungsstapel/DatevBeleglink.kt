package org.example.project.export.datevbuchungsstapel

import java.util.UUID

/**
 * DATEV Buchungsstapel "Beleglink" helper for the Unternehmen-online (BEDI) flow.
 *
 * The Beleglink binds a booking row to a digital Beleg by a GUID that we assign
 * ourselves and also write into document.xml. GUIDs are derived deterministically
 * from the Beleg's identity (its PDF filename) so the CSV and document.xml always
 * agree and re-exports are reproducible.
 */
object DatevBeleglink {
    private const val NAMESPACE = "pos-kmp:datev:beleg:"

    /** Deterministic, uppercase canonical UUID for a Beleg identified by [belegKey] (its PDF filename). */
    fun guidFor(belegKey: String): String = UUID.nameUUIDFromBytes((NAMESPACE + belegKey).toByteArray(Charsets.UTF_8)).toString().uppercase()

    /**
     * The value to hand to `DatevBuchungsstapelCsvWriter.Cell.Str` for column 20.
     * Inner quotes are doubled so the writer's outer quotes yield DATEV's
     * `"BEDI ""<GUID>"""` field.
     */
    fun cellValue(guid: String): String = "BEDI \"\"$guid\"\""
}
