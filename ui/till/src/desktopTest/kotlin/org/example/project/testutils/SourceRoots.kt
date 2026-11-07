package org.example.project.testutils

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source roots every source-scanning guard of `:ui-till`'s desktop tests reads, kept in one place
 * so that a file moving between modules cannot silently drop out of a guard's view. The `:pos-server`
 * test tree has the same helper for the server's guards.
 *
 * The ui modules (`ui/till`, `ui/shared`, `ui/backoffice`) stay where they are. `shared` is being
 * split by stage B2-1b: its platform content — the string keys and translations among it — moves to
 * `platform/api`, which [sharedCommonMainRoots] includes next to `shared`. Every root is
 * mandatory: [kotlinFiles] fails when it is missing instead of letting the guard pass
 * on an empty scan, and each guard additionally asserts a scanned-file floor through [requireScanned].
 *
 * Paths resolve from the repository root, found by walking up from the test's working directory
 * (`ui/till/` under Gradle) to the directory holding `settings.gradle.kts`.
 */
object SourceRoots {
    /** The `src` directory name every root is built from — one place to break them all in a red-proof run. */
    private const val SRC = "src"

    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    /** `ui/till/src/<sourceSet>/kotlin`. */
    fun tillRoot(sourceSet: String = "commonMain"): File = root("ui/till", sourceSet)

    /** `ui/shared/src/<sourceSet>/kotlin` (the `:ui-shared` module). */
    fun uiSharedRoot(sourceSet: String = "commonMain"): File = root("ui/shared", sourceSet)

    /** `ui/backoffice/src/<sourceSet>/kotlin`. */
    fun uiBackofficeRoot(sourceSet: String = "wasmJsMain"): File = root("ui/backoffice", sourceSet)

    /**
     * `platform/api`, `ecr/api`, `fiscal/api`, `accounting/api` and `backoffice/api` `src/commonMain/kotlin` (all mandatory).
     * `:shared` is gone (stage B2-5 C3), so these five are the whole shared surface.
     */
    fun sharedCommonMainRoots(): List<File> =
        listOf("platform/api", "ecr/api", "fiscal/api", "accounting/api", "backoffice/api").map { module -> root(module, "commonMain") }

    /**
     * Every `.kt` file under [roots]; each root must exist and hold at least one, so a vanished or
     * emptied one fails rather than scans nothing.
     */
    fun kotlinFiles(roots: List<File>): List<File> =
        roots
            .flatMap { root ->
                assertTrue(root.isDirectory, "source root ${root.absolutePath} is missing — the guard would pass vacuously")
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also {
                    assertTrue(it.isNotEmpty(), "source root ${root.absolutePath} holds no .kt file — the guard would scan nothing there")
                }
            }.distinctBy { it.canonicalPath } // a file reachable through two overlapping root lists is counted and scanned once

    /**
     * The one directory at [relativePath] under [roots] that holds Kotlin sources. Zero fails (the
     * guard would read nothing), and so do two — the content would be split between modules, and the
     * guard would read one half as if it were the whole. A directory left empty by a move is ignored.
     */
    fun sourceDirUnder(
        roots: List<File>,
        relativePath: String,
    ): File {
        val matches =
            roots
                .map { File(it, relativePath) }
                .filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension == "kt" } }
        assertEquals(1, matches.size, "expected exactly one $relativePath with sources under ${roots.map { it.path }}; found $matches")
        return matches.single()
    }

    /** The one file at [relativePath] under any of [roots]; zero or two matches fail. */
    fun fileUnder(
        roots: List<File>,
        relativePath: String,
    ): File {
        val matches = roots.map { File(it, relativePath) }.filter { it.isFile }
        assertEquals(1, matches.size, "expected exactly one $relativePath under ${roots.map { it.path }}; found $matches")
        return matches.single()
    }

    /**
     * Fails when a guard scanned fewer files than it did when its floor was written. The floor is a
     * guard on the scan, not on the code: raise it freely, never lower it to make a failure go away.
     */
    fun requireScanned(
        guard: String,
        scanned: Int,
        minimum: Int,
    ) {
        assertTrue(
            scanned >= minimum,
            "$guard scanned $scanned files, expected at least $minimum — a source root moved or vanished",
        )
    }

    private fun root(
        moduleDir: String,
        sourceSet: String,
    ): File = File(repoRoot, "$moduleDir/$SRC/$sourceSet/kotlin")
}
