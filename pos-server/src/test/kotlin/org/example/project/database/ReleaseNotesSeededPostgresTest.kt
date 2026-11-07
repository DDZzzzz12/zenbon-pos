package org.example.project.database

import org.example.project.features.changelog.database.tables.ChangelogReleaseTable
import org.example.project.features.changelog.domain.models.ChangelogRelease
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A **fresh** install must carry the release notes the marker migrations insert.
 *
 * `V80__release_1_0_10.sql` and `V90__release_1_1_0.sql` are *data-only* migrations that INSERT into
 * `changelog_release`, and application code is read-only against that table. A fresh install runs
 * neither: `initDB` builds the schema with Exposed and then calls `FlywayConfig.baselineAtLatest`,
 * which marks both applied **without executing them**. The "What's new" panel was therefore
 * permanently empty on every freshly installed shop, for every release up to the baseline, and
 * nothing would ever have filled it in.
 *
 * `SchemaManager.seedReleaseNotes` closes that by executing the marker scripts off the classpath —
 * the SQL files stay the single source of the notes (root CLAUDE.md rule 13); nothing is transcribed
 * into Kotlin, where the second copy would drift from the first.
 *
 * **PostgreSQL, not H2, and that is the point.** The markers are production migrations with `::jsonb`
 * casts; H2 accepts the cast and stores each array as a JSON *string*, so an H2 run would report
 * success and leave rows every later read answers 400 on. The default suite covers the filename
 * selection ([ReleaseMarkerSelectionTest]); only this class covers the SQL actually running.
 *
 * The install under test is genuinely fresh: `initDB(runMigrations = false)` against an empty
 * embedded PostgreSQL builds the schema with Exposed exactly as the production fresh path does,
 * without Flyway ever touching the database.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A fresh PostgreSQL install seeds the release notes")
class ReleaseNotesSeededPostgresTest {
    private lateinit var postgres: PostgresTestDatabase

    @BeforeAll
    fun setUp() {
        postgres = PostgresTestDatabase.start()
        initDB(postgres.hikariConfig(), runMigrations = false)
    }

    @AfterAll
    fun tearDown() {
        postgres.close()
    }

    private fun storedVersions(): List<String> =
        transaction {
            ChangelogReleaseTable.selectAll().map { it[ChangelogReleaseTable.id].value }.sorted()
        }

    /** The releases the marker files on disk describe, so a later marker is covered without an edit. */
    private fun expectedVersions(): List<String> = SchemaManager.releaseMarkerScripts().map { it.releaseVersion }.sorted()

    @Test
    fun `every release marker on disk has produced its row`() {
        val expected = expectedVersions()
        assertTrue(expected.isNotEmpty(), "no release markers found — this test has lost its subject")

        assertContentEquals(
            expected,
            storedVersions(),
            "a fresh install is missing release notes: the What's new panel shows nothing for these releases",
        )
    }

    @Test
    fun `the seeded rows carry all three languages`() {
        // The reason for running the marker SQL verbatim instead of re-typing it in Kotlin. Were the
        // execution to half-work — a cast rejected, a column skipped — the row would exist and the
        // panel would still be blank.
        transaction {
            SchemaManager.releaseMarkerScripts().forEach { marker ->
                val row = ChangelogRelease.findById(marker.releaseVersion)
                assertNotNull(row, "no row for release ${marker.releaseVersion}")

                listOf(row.titleEn, row.titleDe, row.titleZh, row.summaryEn, row.summaryDe, row.summaryZh)
                    .forEach { assertTrue(it.isNotBlank(), "blank text on release ${marker.releaseVersion}") }
                listOf(row.highlightsEn, row.highlightsDe, row.highlightsZh)
                    .forEach { assertTrue(it.isNotEmpty(), "no highlights on release ${marker.releaseVersion}") }
            }
        }
    }

    @Test
    fun `re-seeding neither duplicates nor overwrites`() {
        val version = expectedVersions().first()
        val edited = "edited by the operator"
        transaction { ChangelogRelease.findById(version)!!.titleEn = edited }

        SchemaManager.bootstrapInitialData()

        assertContentEquals(expectedVersions(), storedVersions(), "seeding must not insert a release twice")
        assertEquals(
            edited,
            transaction { ChangelogRelease.findById(version)!!.titleEn },
            "an existing release row must be skipped, not re-applied — re-running the INSERT would " +
                "fail on the primary key on every single boot",
        )
    }

    @Test
    fun `a missing release row is restored on the next boot`() {
        val version = expectedVersions().last()
        transaction { ChangelogReleaseTable.deleteWhere { ChangelogReleaseTable.id eq version } }
        assertTrue(version !in storedVersions())

        SchemaManager.bootstrapInitialData()

        assertContentEquals(expectedVersions(), storedVersions())
    }
}
