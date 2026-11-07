package org.example.project.tse

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the bundled Swissbit SDK against a silent downgrade.
 *
 * Reads `lib/WormAPI.jar` straight off disk rather than off the classpath: on hosts
 * without a TSE the build swaps the `virtualWorm` source set in for the jar
 * (see `build.gradle.kts`), so a classpath-based check would inspect the test
 * double instead of the vendor artifact.
 *
 * TSE 2.0 hardware is reachable only from SDK >= 6.0, so a downgrade below that
 * silently breaks every TSE 2.0 device in the field.
 */
class BundledSdkVersionTest {

    private fun wormApiJar(): File {
        // Gradle runs tests with the module directory (pos-tse) as the working directory.
        val jar = File("lib/WormAPI.jar")
        assertTrue(jar.isFile, "lib/WormAPI.jar not found at ${jar.absolutePath}")
        return jar
    }

    /**
     * Extracts identifier-like strings from a class file's raw bytes. Method and field
     * names live in the constant pool as plain UTF-8, so scanning the bytes finds them
     * without loading the class — loading would resolve against the virtual double.
     */
    private fun identifiersIn(jar: File, entryName: String): Set<String> =
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("$entryName missing from ${jar.name}")
            val bytes = zip.getInputStream(entry).readBytes()
            Regex("[A-Za-z_][A-Za-z0-9_]{3,}")
                .findAll(String(bytes, Charsets.ISO_8859_1))
                .map { it.value }
                .toSet()
        }

    @Test
    fun `bundled WormAPI jar is SDK 6_0_0 or newer`() {
        val identifiers = identifiersIn(wormApiJar(), "com/secureflashcard/wormapi/WormStore.class")

        // All introduced in 6.0.0; absent from every 5.x release.
        listOf("tse_setup_ext", "tse_needs_setup", "tse_autopilot_enable", "user_change_puk_ext")
            .forEach {
                assertTrue(it in identifiers, "WormStore is missing $it — bundled SDK is older than 6.0.0")
            }
    }

    @Test
    fun `bundled WormAPI jar exposes the TSE 2_0 user and credential surface`() {
        val userIds = identifiersIn(wormApiJar(), "com/secureflashcard/wormapi/WormUserId.class")
        assertTrue(
            "WORM_USER_LOGGER" in userIds,
            "WormUserId is missing WORM_USER_LOGGER — bundled SDK predates TSE 2.0",
        )

        val creds = identifiersIn(wormApiJar(), "com/secureflashcard/wormapi/WormInitialCredentials.class")
        listOf("timeAdminPuk", "loggerPuk", "loggerPin")
            .forEach {
                assertTrue(it in creds, "WormInitialCredentials is missing $it — bundled SDK predates TSE 2.0")
            }
    }
}
