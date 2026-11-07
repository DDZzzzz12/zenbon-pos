package org.example.project.testutils

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source roots every source-scanning guard of `:pos-server` reads, kept in one place so that a
 * file moving between modules cannot silently drop out of a guard's view.
 *
 * Each list names a logical source tree: the module as it stands today plus the module its content
 * was extracted into by stage B2-1b (`pos-server` → `platform/server`, the data blob →
 * `platform/data`, `shared` → `platform/api`), and the same for the fiscal tiers (stage B2-3: `fiscal/server|data|api`, skipped while empty, mandatory when their move lands) and for the ecr tiers (stage B2-2: `ecr/server`, `ecr/data`, `ecr/api`). The platform
 * halves are mandatory; an ecr root is skipped while its module has no sources and becomes mandatory when its move lands.
 * Every root is mandatory: [kotlinFiles] fails when it is missing instead of letting
 * the guard pass on an empty scan, and each guard additionally asserts a scanned-file floor through
 * [requireScanned].
 *
 * Paths resolve from the repository root, found by walking up from the test's working directory
 * (`pos-server/` under Gradle) to the directory holding `settings.gradle.kts`.
 */
object SourceRoots {
    /** The `src` directory name every root is built from — one place to break them all in a red-proof run. */
    private const val SRC = "src"

    /** The Kotlin source sets of the area api modules (`platform/api` and the rest). */
    private val SHARED_MAIN_SETS = listOf("commonMain", "jvmMain", "androidMain", "wasmJsMain")

    /** Area folders whose children are modules (spec §6); modules may also sit directly under the repo root. */
    private val AREA_FOLDERS = listOf("platform", "accounting", "fiscal", "ecr", "backoffice")

    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("no settings.gradle.kts above ${File("").absolutePath}")
    }

    /**
     * The main Kotlin roots of every extracted server/data module plus `pos-server` and the area api modules.
     */
    fun kotlinMainRoots(): List<File> = (serverMainRoots() + platformDataMainRoots() + sharedMainRoots()).distinctBy { it.canonicalPath }

    /**
     * `pos-server/src/main/kotlin` plus `platform/server/src/main/kotlin` and `ecr/data/src/main/kotlin`: the data move
     * (stage B2-2) took eight server-side files (`ProcessDataBuilder`, the TSE builders, ...) out of `pos-server`, and the
     * guards that scan these roots must keep reading them. `ecr/data` is included whole, deliberately: it is server-side
     * main code (tables, repositories, TSE builders) that these guards read before the move too, as part of the data blob
     * or `pos-server`. Their floors count it (934 files). [kotlinFiles] de-duplicates the overlap with [platformDataMainRoots].
     * `ecr/server/src/main/kotlin` is mandatory since the server move (stage B2-2): the ecr routes and services left
     * `pos-server` for it.
     */
    fun serverMainRoots(): List<File> =
        // fiscal/data is mandatory since the data move, fiscal/server since the server move (stage B2-3)
        listOf(
            root("pos-server", "main"),
            root("platform/server", "main"),
            root("ecr/data", "main"),
            root("ecr/server", "main"),
            root("fiscal/data", "main"),
            root("fiscal/server", "main"),
        ) +
            // accounting/data and accounting/server are mandatory since their moves (stage B2-4)
            listOf(root("accounting/data", "main"), root("accounting/server", "main")) +
            // backoffice/data and backoffice/server are mandatory since their moves (stage B2-5)
            listOf(root("backoffice/data", "main"), root("backoffice/server", "main"))

    /** `pos-server/src/test/kotlin` plus `platform/server/src/test/kotlin` and `ecr/server/src/test/kotlin` (mandatory since the server move). */
    fun serverTestRoots(): List<File> =
        listOf(
            root("pos-server", "test"),
            root("platform/server", "test"),
            root("ecr/server", "test"),
            root("fiscal/server", "test"),
            root("accounting/server", "test"),
            root("backoffice/server", "test"),
        )

    /** `platform/data/src/main/kotlin`: the one data root [serverMainRoots] does not list (mandatory); `ExceptionConverter` lives here. */
    fun platformDataMainRoots(): List<File> = listOf(root("platform/data", "main"))

    /** Every main source set of `platform/api`, plus the area api modules' commonMain and further sets (mandatory commonMain). `:shared` is gone (stage B2-5 C3). */
    fun sharedMainRoots(): List<File> =
        SHARED_MAIN_SETS.map { root("platform/api", it) } +
            listOf(root("ecr/api", "commonMain")) + // mandatory since the api move (stage B2-2); ecr/api has commonMain only
            SHARED_MAIN_SETS.drop(1).flatMap { ecrRoots("ecr/api", it) } +
            listOf(root("fiscal/api", "commonMain")) + // mandatory since the api move (stage B2-3); fiscal/api has commonMain sources only
            SHARED_MAIN_SETS.drop(1).flatMap { optionalRoots("fiscal/api", it) } +
            listOf(root("accounting/api", "commonMain")) + // mandatory since the api move (stage B2-4); accounting/api has commonMain sources only
            SHARED_MAIN_SETS.drop(1).flatMap { optionalRoots("accounting/api", it) } +
            listOf(root("backoffice/api", "commonMain")) + // mandatory since the api move (stage B2-5); backoffice/api has commonMain sources only
            SHARED_MAIN_SETS.drop(1).flatMap { optionalRoots("backoffice/api", it) }

    /** `<moduleDir>/src/main/kotlin` for modules that are not part of the platform extraction (mandatory). */
    fun moduleMainRoots(vararg moduleDirs: String): List<File> = moduleDirs.map { root(it, "main") }

    /**
     * Every module's `src/main/kotlin`, discovered rather than listed: modules sit directly under the
     * repo root or one level down in an area folder. New modules are picked up the moment they exist.
     */
    fun allModuleMainRoots(): List<File> =
        (listOf(repoRoot) + AREA_FOLDERS.map { File(repoRoot, it) })
            .flatMap { parent -> parent.listFiles()?.map { File(it, "$SRC/main/kotlin") }.orEmpty() }
            .filter { dir ->
                dir.isDirectory &&
                    dir.walkTopDown().any {
                        it.isFile && it.extension == "kt"
                    }
            } // a module emptied by its extraction leaves directories only
            .distinctBy { it.canonicalPath }

    /** Every `.kt` file under [roots]; each root must exist, so a vanished one fails rather than scans nothing. */
    fun kotlinFiles(roots: List<File>): List<File> =
        roots
            .flatMap { root ->
                assertTrue(root.isDirectory, "source root ${root.absolutePath} is missing — the guard would pass vacuously")
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also {
                    assertTrue(it.isNotEmpty(), "source root ${root.absolutePath} holds no .kt file — the guard would scan nothing there")
                }
            }.distinctBy { it.canonicalPath } // a file reachable through two overlapping root lists is counted and scanned once

    /**
     * [file]'s path below its `src/<set>/kotlin` directory with `/` separators (`org/example/…/X.kt`):
     * what a guard filters on, independent of where the repository or the module sits.
     */
    fun pathInRoot(file: File): String = file.invariantSeparatorsPath.replace(Regex("^.*/$SRC/[^/]+/kotlin/"), "")

    /**
     * The one file at [relativePath] (below the `kotlin` directory, e.g.
     * `org/example/project/Application.kt`) under any of [roots]. Zero or two matches fail: the
     * guard would otherwise read nothing, or read one copy and miss the other.
     */
    fun fileUnder(
        roots: List<File>,
        relativePath: String,
    ): File {
        val matches = roots.map { File(it, relativePath) }.filter { it.isFile }
        assertEquals(1, matches.size, "expected exactly one $relativePath under ${roots.map { it.path }}; found $matches")
        return matches.single()
    }

    /** Every existing directory at [relativePath] under [roots]; at least one must exist. */
    fun dirsUnder(
        roots: List<File>,
        relativePath: String,
    ): List<File> {
        val dirs = roots.map { File(it, relativePath) }.filter { it.isDirectory }
        assertTrue(dirs.isNotEmpty(), "no $relativePath under ${roots.map { it.path }} — the guard would pass vacuously")
        return dirs
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

    /**
     * `<moduleDir>/src/<sourceSet>/kotlin` of a tier module, or nothing while that module holds no Kotlin
     * sources yet (the empty-module commits create them empty; the move tasks make their root mandatory by switching to [root]).
     */
    private fun optionalRoots(
        moduleDir: String,
        sourceSet: String,
    ): List<File> = listOf(root(moduleDir, sourceSet)).filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension == "kt" } }

    private fun ecrRoots(
        moduleDir: String,
        sourceSet: String,
    ): List<File> = optionalRoots(moduleDir, sourceSet)

    private fun root(
        moduleDir: String,
        sourceSet: String,
    ): File = File(repoRoot, "$moduleDir/$SRC/$sourceSet/kotlin")
}
