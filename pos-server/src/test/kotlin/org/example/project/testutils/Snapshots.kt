package org.example.project.testutils

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Line snapshots under `pos-server/src/test/resources/`, compared whole.
 *
 * A snapshot is only (re)written when the environment variable [RECORD_FLAG] is `true`; otherwise a
 * missing file fails, so a snapshot can never be silently created by the run that should be checking
 * it. Re-recording means the pinned surface changed on purpose — say so in the commit.
 */
object Snapshots {
    const val RECORD_FLAG = "RECORD_SNAPSHOTS"

    /** Compares [actual] with the snapshot [name], or records it when [RECORD_FLAG] is set. */
    fun assertMatches(
        name: String,
        actual: List<String>,
    ) {
        assertTrue(actual.isNotEmpty(), "nothing to compare against the $name snapshot — the collection broke")
        val file = File(SourceRoots.repoRoot, "pos-server/src/test/resources/$name")
        if (System.getenv(RECORD_FLAG) == "true") {
            file.writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        assertTrue(file.isFile, "snapshot ${file.absolutePath} is missing; record it with $RECORD_FLAG=true")
        val expected = file.readLines().filter { it.isNotBlank() }
        assertEquals(
            expected.joinToString("\n"),
            actual.joinToString("\n"),
            "differs from ${file.name}: removed ${expected - actual.toSet()}, added ${actual - expected.toSet()}",
        )
    }
}
