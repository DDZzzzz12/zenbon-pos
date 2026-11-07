package org.example.project.features.update.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.error.PosErrorCode
import org.example.project.features.update.domain.models.UpdateMarker
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Replaces the running server's jar and exits so systemd restarts into it.
 *
 * ### Nothing supervises this but systemd
 *
 * `install-service.sh` already writes the whole safety net, and this class deliberately adds no
 * second supervisor on top of it:
 * - `ExecStartPre=restart-guard.sh` counts consecutive starts and, after **3**, moves
 *   `pos-server.jar` aside and swaps `pos-server.jar.bak` back in.
 * - `ExecStartPost` polls `/health` for 60s and resets `.restart-count` to 0 on success.
 * - The unit is `Restart=on-failure`, `RestartSec=5`.
 *
 * So the applier's entire job is: make a backup that is known good, swap the jar, record what it
 * did, and exit **non-zero** ([EXIT_UPDATE_APPLIED]). A clean exit would not trigger
 * `Restart=on-failure` and the shop would simply be down. A healthy new build resets the counter;
 * an unhealthy one is reverted automatically after three attempts.
 *
 * ### Ordering is the design
 *
 * Nothing destructive happens before it is proven. The jar is obtained, the database backup is
 * taken *and verified non-empty*, and only then is the running jar copied aside and overwritten.
 * Every abort therefore leaves a server that still boots the binary it is already running, and
 * never calls [exitProcess] -- restarting after an abandoned swap would cost an outage for nothing.
 *
 * ### The rollback is not lossless
 *
 * A jar rollback cannot undo a Flyway migration. If the new build migrates the schema and then
 * fails, `restart-guard.sh` gets the old jar booting again but it faces a newer schema. That is
 * why the pre-apply database backup is mandatory here and why [UpdateMarker] names it: recovery
 * must be one restore, not an investigation.
 *
 * ### Jar-only
 *
 * `jre/`, `native/` and `run.sh` are never replaced. `native/` especially: public `zenbon-pos`
 * artifacts ship deliberately **without** `WormAPI.jar`, so overwriting `native/` from a public
 * artifact would destroy a private install's TSE runtime.
 *
 * ### Transport-agnostic
 *
 * [extractServerJar] hands back a *file*, never a `ByteArray`: the server jar is ~127 MB and
 * reading it into heap on a POS host to copy it back out again is waste the delta path exists to
 * avoid. [serverJarFrom] absorbs the difference between a staged archive and a staged bare jar, so
 * nothing below it knows or cares how the update arrived.
 */
class UpdateApplier(
    private val installDir: File,
    private val createBackup: suspend () -> String,
    private val extractServerJar: (File) -> File,
    private val exitProcess: (Int) -> Unit,
) {
    /**
     * Installs [stagedArtifact] as the running server and exits with [EXIT_UPDATE_APPLIED].
     *
     * @throws PosException if the archive carries no usable server jar, or the pre-apply database
     *   backup is missing or empty. In either case nothing on disk has been touched.
     */
    suspend fun apply(
        version: String,
        stagedArtifact: File,
    ) {
        val newJar = extractServerJar(stagedArtifact)
        if (!newJar.isFile || newJar.length() == 0L) {
            throw abort("applying $version refused: ${stagedArtifact.name} yielded an empty server jar")
        }

        val backupPath = createBackup()
        requireUsableBackup(version, backupPath)

        writeMarker(version, backupPath)
        swapJar(newJar)

        exitProcess(EXIT_UPDATE_APPLIED)
    }

    /** A backup that does not exist, or exists and is empty, is not a backup. Fail before swapping. */
    private fun requireUsableBackup(
        version: String,
        backupPath: String,
    ) {
        val backup = File(backupPath)
        if (!backup.isFile) {
            throw abort("applying $version refused: the pre-apply database backup $backupPath was not written")
        }
        if (backup.length() == 0L) {
            throw abort("applying $version refused: the pre-apply database backup $backupPath is empty")
        }
    }

    private fun writeMarker(
        version: String,
        backupPath: String,
    ) {
        val marker =
            UpdateMarker(
                targetVersion = version,
                databaseBackupPath = backupPath,
                startedAt = Instant.now().toString(),
            )
        File(installDir, MARKER).writeText(JSON.encodeToString(marker))
    }

    /** Copy aside first: `restart-guard.sh` can only roll back to a `.bak` that already exists. */
    private fun swapJar(newJar: File) {
        val jar = File(installDir, SERVER_JAR)
        jar.copyTo(File(installDir, "$SERVER_JAR.bak"), overwrite = true)
        newJar.copyTo(jar, overwrite = true)
    }

    private fun abort(message: String) = PosException(PosErrorCode.UPDATE_APPLY_ABORTED, HttpStatusCode.InternalServerError, message)

    companion object {
        const val MARKER = "update-applying.json"

        /** Non-zero, so `Restart=on-failure` brings the service back. A clean exit would not. */
        const val EXIT_UPDATE_APPLIED = 42

        private const val SERVER_JAR = "pos-server.jar"
        private val JSON = Json { prettyPrint = true }

        /**
         * The production [extractServerJar]: accepts either shape of staged artifact.
         *
         * A delta or bare-jar download stages `pos-server-<version>.jar` directly and there is
         * nothing to unpack; the full-zip path -- still live, because releases published before
         * the bare jar existed carry only an archive -- goes through [extractServerJarFromZip].
         * Both reach the same swap, so the applier stays ignorant of how the update travelled.
         */
        fun serverJarFrom(staged: File): File {
            if (staged.extension.equals("jar", ignoreCase = true)) return staged
            return extractServerJarFromZip(staged)
        }

        /**
         * Streams `pos-server.jar` out of the staged deploy zip and returns the file it wrote.
         *
         * Matched on the **basename**, because the archive may or may not carry a top-level
         * directory, and never from `native/` -- that is where the Swissbit `WormAPI.jar` lives on
         * a private install and it is not the server. An archive with no such entry is refused
         * with [PosErrorCode.UPDATE_APPLY_ABORTED] and nothing is written.
         *
         * ### Where the bytes land
         *
         * Streamed, never read into a `ByteArray`: the entry is ~127 MB on a real release and the
         * POS host's heap is not sized for it. The destination is therefore a deliberate choice --
         * `<archive dir>/<archive name without extension>-extracted.jar`:
         * - **Beside the archive**, because that directory has already been proven to hold a
         *   release-sized file, and because [UpdateStager.pruneExcept] sweeps it, so an abandoned
         *   apply does not leak ~127 MB forever. A system temp directory guarantees neither.
         * - **Named after the archive**, so two versions staged at once cannot overwrite each
         *   other's extraction.
         * - **Distinct from the archive itself** (`.zip` in, `-extracted.jar` out) and from
         *   `pos-server.jar`, which lives in the *installation* directory -- the extraction must
         *   never be able to land on the running binary before the backup is taken.
         *
         * It carries no verified digest of its own, but it does not weaken [UpdateStager]'s
         * invariant: its bytes come out of an archive whose published digest already verified.
         */
        fun extractServerJarFromZip(archive: File): File {
            val target = File(archive.parentFile, "${archive.nameWithoutExtension}-extracted.jar")
            ZipFile(archive).use { zip ->
                val entry =
                    zip
                        .entries()
                        .asSequence()
                        .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == SERVER_JAR }
                        ?: throw PosException(
                            PosErrorCode.UPDATE_APPLY_ABORTED,
                            HttpStatusCode.InternalServerError,
                            "applying refused: ${archive.name} contains no $SERVER_JAR",
                        )
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
            return target
        }
    }
}
