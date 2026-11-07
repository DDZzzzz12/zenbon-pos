package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.LogScrubber
import org.example.project.testutils.SourceRoots
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every logger declared in the auth and employee features must be withheld from the maintainer whole.
 * Those loggers write usernames and login identifiers in shapes no scrubber rule can be trusted with,
 * and a logger named by a plain string (`getLogger("AuthRoutes")`) is NOT under the feature's package —
 * that is exactly how the first version of the rule never fired. A new logger there fails this test
 * until `LogScrubber` withholds it.
 *
 * The feature directories are looked up under [SourceRoots.serverMainRoots], so the guard follows them
 * into `platform/server` when the extraction moves them.
 */
class IdentityLoggerGuardTest {
    private companion object {
        /** Kotlin files under the two feature directories on 2026-10-27, before the platform extraction moved any. */
        const val MINIMUM_SCANNED_FILES = 30
    }

    private val roots: List<File> by lazy {
        listOf("features/auth", "features/employee").flatMap { feature ->
            SourceRoots.dirsUnder(SourceRoots.serverMainRoots(), "org/example/project/$feature")
        }
    }

    private val plainName = Regex("""getLogger\(\s*"([^"]+)"\s*\)""")
    private val classRef = Regex("""getLogger\(\s*([A-Za-z_][A-Za-z0-9_]*)::class""")
    private val packageLine = Regex("""(?m)^package\s+([\w.]+)""")

    private data class Declared(
        val name: String,
        val file: File,
    )

    private fun declaredLoggers(): List<Declared> {
        val files = SourceRoots.kotlinFiles(roots)
        SourceRoots.requireScanned("IdentityLoggerGuardTest", files.size, MINIMUM_SCANNED_FILES)
        return files.flatMap { file ->
            val text = file.readText()
            val pkg =
                packageLine
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            plainName.findAll(text).map { Declared(it.groupValues[1], file) }.toList() +
                classRef.findAll(text).map { Declared("$pkg.${it.groupValues[1]}", file) }
        }
    }

    @Test
    fun `every auth and employee logger is withheld from the maintainer`() {
        val declared = declaredLoggers()
        val names = declared.map { it.name }.toSet()
        listOf("AuthRoutes", "EmployeeRoutes", "ApprovalRoutes", "TemporarySessionRoutes").forEach { known ->
            assertTrue(known in names, "expected to find the logger \"$known\"; found $names")
        }
        val leaking =
            declared.filter { LogScrubber.scrubMessage(it.name, "Login attempt for identifier: anna") != "<redacted: identity log>" }
        assertTrue(
            leaking.isEmpty(),
            "loggers whose messages would reach the maintainer: " + leaking.joinToString { "${it.name} (${it.file.path})" },
        )
    }
}
