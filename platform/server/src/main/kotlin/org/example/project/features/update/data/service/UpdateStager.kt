package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Holds downloaded release artifacts until they are applied.
 *
 * Its one job: **never write an artifact to disk unless its checksum verifies.** Everything
 * downstream -- the installer, a manual retry, an operator poking around in the staging directory
 * -- may assume that a file present here matched its published digest at the moment it was
 * written. Verification therefore happens entirely in memory, before the first byte is opened for
 * writing, and a rejected payload leaves the directory exactly as it found it.
 *
 * ### Which `SHA256SUMS` line is ours
 *
 * A release publishes several artifacts under one checksum file -- the server archive and the
 * Android APK at least, both named `zenbon-pos-…` and both carrying the same version. Matching on
 * the version, or on the `zenbon-pos` prefix, would select whichever line happened to come first
 * and verify the server bytes against the APK's digest: a correct download rejected, or -- far
 * worse, if the digests were ever to be compared the other way round -- a wrong artifact accepted.
 *
 * The rule is therefore the **file extension plus the word `server`**: the entry's basename must
 * end in `.zip` (or, for a delta rebuild, in `.jar`) and contain `server`, case-insensitively.
 * That is deliberately the same predicate `GithubReleaseClient.isServerArchive` uses to pick the
 * *asset*, so the line verified is the line describing the bytes downloaded. Exactly one entry
 * must match; zero or several are both refused rather than guessed at.
 *
 * The extension test is **end-anchored**, and that is load-bearing rather than tidy: the published
 * set carries `pos-server-<V>.jar.cdir.gz` -- the jar's gzipped ZIP central directory -- beside
 * `pos-server-<V>.jar`. A `contains(".jar")` spelling matches both, and "several" is refused, so a
 * perfectly good delta download would be rejected every time.
 *
 * The published name (`zenbon-pos-server-v1.2.0.zip`) is not assumed to equal the staged name --
 * upstream tags carry a `v`, our staged files do not -- so the rule never compares against
 * [stagedName].
 */
class UpdateStager(
    private val stagingDir: File,
) {
    /**
     * Where an artifact is assembled *before* it verifies -- a subdirectory, never [stagingDir].
     *
     * A rebuild writes `rebuilt-<v>.jar` (and its `.part`) and a bare-jar download writes
     * `downloaded-<v>.jar`, both of which exist on disk long before any digest is compared. Writing
     * those into the staging directory itself would make the class KDoc's invariant literally
     * false: a power cut mid-rebuild -- the exact scenario a POS host is built around -- would
     * leave an unverified ~114 MB file in a directory whose contract is that everything in it
     * verified. Nothing exploitable followed from it (only `settings.stagedPath` is ever applied),
     * but a contract that holds only because nobody reads the directory is not a contract.
     *
     * It is exposed rather than derived by the caller so that the work directory and the staging
     * directory cannot drift apart in wiring: the one place that knows the layout is the one class
     * whose invariant depends on it. [pruneExcept] sweeps it like any other entry.
     */
    val workDir: File get() = File(stagingDir, WORK_DIR_NAME)

    /**
     * Verifies [bytes] against the entry for the server archive in [sha256sumsBody] and, only if
     * it matches, writes them as `zenbon-pos-server-<version>.zip`.
     *
     * @return the staged file.
     * @throws PosException with [PosErrorCode.UPDATE_CHECKSUM_MISMATCH] if the checksum body names
     *   no server archive, names it more than once, carries an unusable digest, or the digest
     *   disagrees with [bytes]. Nothing is written in any of those cases.
     */
    fun stage(
        version: String,
        bytes: ByteArray,
        sha256sumsBody: String,
    ): File {
        val expected = expectedDigestFor(version, sha256sumsBody, ARTIFACT_ZIP)
        val actual = sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw checksumFailure(
                "staging $version refused: server archive checksum mismatch " +
                    "(expected $expected, downloaded $actual)",
            )
        }

        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw checksumFailure("staging $version refused: ${stagingDir.absolutePath} could not be created")
        }
        val target = File(stagingDir, stagedName(version))
        target.writeBytes(bytes)
        return target
    }

    /**
     * Moves an already-reconstructed jar into staging, if [actualDigest] matches the published one.
     *
     * **This CONSUMES [rebuilt].** On success the file is moved, not copied: source and target sit
     * in the same directory tree, so a copy was ~114 MB of pointless I/O and a 2x transient disk
     * high-water mark on the very path whose purpose is to stop moving a jar around. On failure
     * [rebuilt] is untouched and still exists, which is why both call sites keep deleting it in a
     * `finally`.
     *
     * The jar arrives as a file plus its digest rather than as a `ByteArray` because a delta
     * rebuild writes ~127 MB and hashes it while streaming; holding that in heap on a POS host
     * only to re-hash it would be pure waste. The invariant is unchanged: nothing enters the
     * staging directory unless its published digest verified first, and a rejected jar leaves the
     * directory exactly as it found it.
     *
     * @return the staged file.
     * @throws PosException with [PosErrorCode.UPDATE_CHECKSUM_MISMATCH] on the same four grounds
     *   as [stage], read against the release's `.jar` line instead of its `.zip` line.
     */
    fun stageJar(
        version: String,
        rebuilt: File,
        actualDigest: String,
        sha256sumsBody: String,
    ): File {
        val expected = expectedDigestFor(version, sha256sumsBody, ARTIFACT_JAR)
        if (!actualDigest.equals(expected, ignoreCase = true)) {
            throw checksumFailure(
                "staging $version refused: rebuilt jar checksum mismatch " +
                    "(expected $expected, rebuilt $actualDigest)",
            )
        }

        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw checksumFailure("staging $version refused: ${stagingDir.absolutePath} could not be created")
        }
        val target = File(stagingDir, stagedJarName(version))
        Files.move(rebuilt.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Deletes everything in the staging directory except [keep].
     *
     * It takes the file that was actually staged rather than a version, because there are now two
     * shapes of staging -- `zenbon-pos-server-<v>.zip` from a full download and
     * `pos-server-<v>.jar` from a delta rebuild or a bare-jar download -- and a version-derived
     * name can only name one of them. The earlier spelling derived the zip name, so pruning right
     * after a delta staged a jar deleted the artifact of that very run: every delta update would
     * have reported success and left an empty staging directory behind.
     *
     * A file outside the staging directory is refused rather than quietly pruning everything: the
     * caller would have asked to keep something this method cannot see, and deleting the whole
     * directory is not a reasonable reading of that.
     */
    fun pruneExcept(keep: File) {
        require(keep.parentFile?.canonicalFile == stagingDir.canonicalFile) {
            "${keep.absolutePath} is not in the staging directory ${stagingDir.absolutePath}"
        }
        stagingDir.listFiles()?.forEach { file ->
            if (file.name != keep.name) file.deleteRecursively()
        }
    }

    private fun stagedName(version: String) = "zenbon-pos-server-$version.zip"

    fun stagedJarName(version: String) = "pos-server-$version.jar"

    /**
     * The published digest of the Android APK. Public because [ClientArtifactStore] owns the APK's
     * directory but must not reimplement the "exactly one line, extension-anchored" rule that this
     * class's KDoc explains.
     *
     * @throws PosException with [PosErrorCode.UPDATE_CHECKSUM_MISMATCH] if the checksum body names
     *   no APK, names several, or carries an unusable digest.
     */
    fun expectedApkDigest(
        version: String,
        sha256sumsBody: String,
    ): String = expectedDigestFor(version, sha256sumsBody, ARTIFACT_APK)

    /** Fails fast with a message naming the actual problem -- see the class KDoc for the rule. */
    private fun expectedDigestFor(
        version: String,
        body: String,
        artifact: Artifact,
    ): String {
        val entries =
            body
                .lineSequence()
                .mapNotNull(::parseLine)
                .filter { artifact.matches(it.name) }
                .toList()
        if (entries.isEmpty()) {
            throw checksumFailure("staging $version refused: the checksum file names no ${artifact.label}")
        }
        if (entries.size > 1) {
            throw checksumFailure(
                "staging $version refused: the checksum file names ${entries.size} ${artifact.label}s " +
                    "(${entries.joinToString { it.name }})",
            )
        }
        val entry = entries.single()
        if (!HEX_64.matches(entry.digest)) {
            throw checksumFailure(
                "staging $version refused: the checksum entry for ${entry.name} is not a SHA-256 digest",
            )
        }
        return entry.digest
    }

    /**
     * One `sha256sum`-format line: a digest, two separator characters (the second is a space for
     * text mode and `*` for binary mode), then the filename. Blank lines and anything that does
     * not split into two fields are ignored -- a checksum file may carry comments.
     */
    private fun parseLine(line: String): Entry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val split = trimmed.indexOf(' ')
        if (split <= 0) return null
        val name = trimmed.substring(split).trimStart(' ', '*').trim()
        if (name.isEmpty()) return null
        return Entry(digest = trimmed.substring(0, split), name = name.substringAfterLast('/'))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun checksumFailure(message: String) = PosException(PosErrorCode.UPDATE_CHECKSUM_MISMATCH, HttpStatusCode.BadGateway, message)

    private data class Entry(
        val digest: String,
        val name: String,
    )

    /** Which `SHA256SUMS` line describes the bytes we are about to stage -- see the class KDoc. */
    private class Artifact(
        val label: String,
        private val predicate: (String) -> Boolean,
    ) {
        fun matches(name: String) = predicate(name.lowercase())
    }

    private companion object {
        const val WORK_DIR_NAME = "work"

        val HEX_64 = Regex("[0-9a-fA-F]{64}")

        val ARTIFACT_ZIP = Artifact("server archive") { it.endsWith(".zip") && it.contains("server") }

        /**
         * End-anchored on purpose: `pos-server-<V>.jar.cdir.gz` is published alongside
         * `pos-server-<V>.jar`, so `contains(".jar")` would match two lines and refuse.
         */
        val ARTIFACT_JAR = Artifact("server jar") { it.endsWith(".jar") && it.contains("server") }

        /** End-anchored; deliberately has no "server" test, the APK is published as `zenbon-pos-<V>.apk`. */
        val ARTIFACT_APK = Artifact("Android APK") { it.endsWith(".apk") }
    }
}
