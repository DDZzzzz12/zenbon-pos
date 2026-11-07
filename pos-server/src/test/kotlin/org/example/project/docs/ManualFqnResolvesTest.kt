package org.example.project.docs

import org.example.project.testutils.SourceRoots
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every fully-qualified class name the shipped manual quotes must name a class that exists, in
 * exactly one source file (spec §15 C(b)).
 *
 * Operators search logs for these strings: `kap-16-fehlerbehebung.html` quotes the post-restore
 * hook's class name as it appears in the ERROR line. Nothing compiles the manual, so a moved,
 * renamed or deleted class would leave it quoting a string the log no longer contains.
 *
 * A token is an `org.example.project.…` run in an HTML page of `manual/`. Its first segment that starts
 * with an upper-case letter is the class; the segments before it are the package, and the class must
 * be declared by exactly one `.kt` file directly in that package directory under the source roots —
 * [SourceRoots.kotlinMainRoots] (the platform blobs and their `platform/api|data|server` successors) plus every
 * other module's main sources, so a quoted class may live in any module. A token with no upper-case
 * segment is a package prefix (`org.example.project.features`, quoted as a log category) and is
 * allowed: it names a logger hierarchy, not a class.
 */
class ManualFqnResolvesTest {
    private companion object {
        val TOKEN = Regex("""org\.example\.project(?:\.\w+)+""")

        /** Manual pages on 2026-10-27; a floor, so a moved manual directory fails instead of scanning nothing. */
        const val MINIMUM_PAGES = 25

        /** Class-naming tokens on 2026-10-27; a floor, so a broken token pattern fails instead of finding none. */
        const val MINIMUM_CLASS_TOKENS = 1
    }

    private val manualDir = File(SourceRoots.repoRoot, "pos-server/src/main/resources/manual")

    private val roots: List<File> by lazy {
        (SourceRoots.kotlinMainRoots() + SourceRoots.allModuleMainRoots()).distinctBy { it.canonicalPath }
    }

    /** The `.kt` files directly in [packagePath] under any root that declare a class, interface or object [name]. */
    private fun declaringFiles(
        packagePath: String,
        name: String,
    ): List<File> {
        val declaration = Regex("""\b(class|interface|object)\s+$name\b""")
        return roots
            .map { File(it, packagePath) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "kt" } }
            .filter { declaration.containsMatchIn(it.readText()) }
    }

    @Test
    fun `every class the manual quotes resolves to exactly one source file`() {
        val pages = manualDir.listFiles().orEmpty().filter { it.isFile && it.extension == "html" }
        assertTrue(pages.size >= MINIMUM_PAGES, "found ${pages.size} manual pages under ${manualDir.absolutePath}")

        val classTokens =
            pages
                .flatMap { page -> TOKEN.findAll(page.readText()).map { page.name to it.value }.toList() }
                .distinct()
                .mapNotNull { (page, token) ->
                    val segments = token.split('.')
                    val classIndex = segments.indexOfFirst { it.first().isUpperCase() }
                    if (classIndex < 0) null else Triple(page, token, segments.take(classIndex + 1))
                }
        assertTrue(classTokens.size >= MINIMUM_CLASS_TOKENS, "found only ${classTokens.size} class names in the manual")

        val unresolved =
            classTokens.mapNotNull { (page, token, segments) ->
                val files = declaringFiles(segments.dropLast(1).joinToString("/"), segments.last())
                if (files.size == 1) null else "$page: $token -> ${files.map { it.path }}"
            }
        assertEquals(emptyList(), unresolved, "manual names classes that resolve to zero or several source files")
    }
}
