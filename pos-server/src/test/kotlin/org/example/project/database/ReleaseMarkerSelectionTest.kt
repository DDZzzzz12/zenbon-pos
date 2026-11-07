package org.example.project.database

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Which migration scripts `SchemaManager.seedReleaseNotes` replays on a fresh install.
 *
 * A fresh install never runs Flyway — `initDB` builds the schema with Exposed and baselines — so the
 * data-only release markers, and therefore the whole "What's new" panel, were skipped. The fix
 * executes those scripts off the classpath, which makes *selecting the right ones* the sharp edge:
 * `V77__release_core_features.sql`, `V78__release_fiscal_realignment.sql` and
 * `V79__release_tse_audit_terminal.sql` are the squashed **schema** groups, full of DDL, and they
 * also begin `release_`. Replaying one of those would re-run CREATE TABLE against a schema Exposed
 * has just built.
 *
 * Needs no database: selection is a filename decision, and pinning it here keeps it in the default
 * (H2) suite even though the seeding itself is PostgreSQL-only — see
 * [ReleaseNotesSeededPostgresTest].
 */
@DisplayName("Release markers are selected by the rule-13 filename convention")
class ReleaseMarkerSelectionTest {
    private val selected = SchemaManager.releaseMarkerScripts()

    @Test
    fun `the release markers on disk are found`() {
        // `containsAll`, not equality: a later release adds a marker, and that must not be a test to
        // update. What has to hold is that the two shipped ones are still picked up, in release
        // order, with the version their INSERT writes.
        assertTrue(
            selected.map { it.script to it.releaseVersion }.containsAll(
                listOf("V80__release_1_0_10.sql" to "1.0.10", "V90__release_1_1_0.sql" to "1.1.0"),
            ),
            "the shipped release-note migrations are no longer replayed on a fresh install: $selected",
        )
    }

    @Test
    fun `the squashed schema groups are not mistaken for release markers`() {
        val scripts = selected.map { it.script }

        listOf(
            "V77__release_core_features.sql",
            "V78__release_fiscal_realignment.sql",
            "V79__release_tse_audit_terminal.sql",
        ).forEach { ddlGroup ->
            assertTrue(
                ddlGroup !in scripts,
                "$ddlGroup is a squashed schema group, not a release marker — replaying it would " +
                    "re-run DDL against a schema Exposed already built",
            )
        }
    }

    @Test
    fun `every selected script is named for the release it inserts`() {
        // The version is derived from the filename, never from the SQL, and it is what makes the
        // seeding idempotent: it is the `release_version` primary key the script's INSERT writes.
        // A script whose name did not carry it would be re-applied on every boot and fail on the
        // primary key each time.
        selected.forEach { marker ->
            assertTrue(
                marker.script.endsWith("__release_${marker.releaseVersion.replace('.', '_')}.sql"),
                "${marker.script} does not name release ${marker.releaseVersion}",
            )
        }
    }
}
