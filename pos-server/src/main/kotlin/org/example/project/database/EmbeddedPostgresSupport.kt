package org.example.project.database

import org.example.project.utils.OSType
import org.example.project.utils.OsUtils

/**
 * Whether this host can run the bundled embedded PostgreSQL, and what to say when it cannot.
 *
 * A released jar ships zonky's binaries for the two **deployment** platforms only, both x86_64:
 * `postgres-linux-x86_64` and `postgres-windows-x86_64`. There is no arm64 archive for any
 * platform. Without this check an ARM64 Linux host dies at boot with
 * `IllegalStateException: Missing embedded postgres binaries` and a zonky stack trace naming a
 * Maven coordinate, which tells an operator nothing they can act on.
 *
 * **macOS resolves a database binary only when the server is run from Gradle.** `darwin-amd64` sits
 * in the `devDatabaseBinaries` configuration, which `run`/`runVirtual` add to their classpath and
 * shadowJar never sees, so a released jar on macOS carries none. macOS is not a deployment target —
 * there is no run script for it, and the TSE driver ships x86 Linux/Windows libraries only.
 *
 * **The gate is Linux-only on purpose.** macOS on Apple Silicon reports `os.arch=aarch64` and works
 * perfectly well under Gradle, because Rosetta runs the x86_64 postgres transparently. Failing on
 * architecture alone would break every Apple Silicon development machine. Linux has no such
 * translation layer, so there the missing binary is fatal.
 *
 * **x86_64 is required for a fiscal install regardless of the database.** The Swissbit TSE SDK
 * ships JNI libraries for `linux32`, `linux64`, `windows32` and `windows64` — all x86, no ARM — and
 * since the 2026-08 in-process-only change the TSE must run on the server host rather than a
 * separate connector. So an ARM64 host cannot sign a receipt whatever database it uses. That is why
 * the message below points at the architecture rather than merely suggesting `DATABASE_MODE=external`:
 * external mode would let such a host boot and only discover the real problem at the first sale.
 */
object EmbeddedPostgresSupport {
    /** What the bundled binaries actually cover. `amd64` is the same thing under another name. */
    private val SUPPORTED_ARCHITECTURES = setOf("x86_64", "amd64")

    fun currentArchitecture(): String = System.getProperty("os.arch").orEmpty()

    /**
     * False only where the missing binary is genuinely fatal: Linux on a non-x86_64 architecture.
     *
     * Both parameters are injectable so the decision can be tested for hosts this machine is not.
     */
    fun isSupported(
        architecture: String = currentArchitecture(),
        osType: OSType = OsUtils.detectOS(),
    ): Boolean = osType != OSType.LINUX || architecture.lowercase() in SUPPORTED_ARCHITECTURES

    /** Names the cause, the consequence and the two ways out, in that order. */
    fun unsupportedMessage(architecture: String = currentArchitecture()): String =
        "Embedded PostgreSQL is not available on Linux/$architecture: this build bundles x86_64 " +
            "database binaries only. Run an external PostgreSQL instead " +
            "(DATABASE_MODE=external, DATABASE_URL=jdbc:postgresql://<host>:5432/<db>, " +
            "DATABASE_USERNAME, DATABASE_PASSWORD), or install the server on an x86_64 host. " +
            "Note that a fiscal installation needs x86_64 in any case: the TSE driver ships x86 " +
            "libraries only and runs in the server process, so an $architecture host cannot sign " +
            "receipts even with an external database."
}
