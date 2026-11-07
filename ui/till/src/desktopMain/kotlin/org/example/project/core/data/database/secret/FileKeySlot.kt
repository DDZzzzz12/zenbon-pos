package org.example.project.core.data.database.secret

import org.example.project.BuildKonfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [FallbackKeySlot] in a plain file, for a till with no usable OS keychain (a Linux kiosk without a keyring daemon), one
 * whose keychain could not be read when the key was created, or one whose keychain does not answer at all (a locked
 * keyring on an auto-login kiosk).
 *
 * On POSIX systems the file is `rw-------`, and a folder a write creates is `rwx------`, so no other local account can read
 * the key; on Windows it inherits the per-user ACL of `%LOCALAPPDATA%`. It protects against a copied database, not against
 * the till's own OS user — and a backup of the whole home directory includes this file, and with it the key.
 *
 * **Only ever created.** A value sealed under the file key is lost the moment that key is replaced, so this slot offers no
 * write that replaces ([ReplaceableKeySlot] is the keychain's).
 *
 * **Creating.** [writeIfAbsent] never replaces an existing file. The key goes into a new temp file in the same folder,
 * which is forced to disk and then hard-linked to the key file's name, so no reader ever sees a half-written key. The link
 * fails atomically, even against another process, when a key file appeared meanwhile; the key found there is returned
 * instead. The temp name is removed either way. Where the file system has no hard links, an in-process lock, a fresh check
 * and a move that refuses to replace an existing file ([moveWithoutReplacing]) serialize creation instead. That protects
 * against other threads of this process; another process can still slip in between the check and the move, which on POSIX
 * are two steps.
 *
 * **Leftover temp names.** A crash between the link and removing the temp name leaves `.local-secrets.key.<uuid>.tmp`
 * behind: a second name for the key, which survives deleting the key file. [writeIfAbsent] removes such names older than
 * [STALE_TEMP_AGE], best effort, measured against [clock] (wall-clock milliseconds, as file times are). A younger one may
 * belong to a writer still at work, and is left alone. So is any name whose middle part is not a canonical UUID.
 *
 * **A garbled key file is never overwritten.** Every write goes through a forced temp file, so this class never leaves a
 * half-written key file behind. One that is empty or not Base64 was changed from outside. It is not provably garbage, so
 * [writeIfAbsent] throws rather than replace it: creating a key fails with a logged error until someone removes the file.
 *
 * On POSIX the folder is flushed after a file is put in place, so a power cut cannot lose the new file's name.
 */
class FileKeySlot internal constructor(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createLink: (link: Path, existing: Path) -> Unit,
) : FallbackKeySlot {
    /** A slot kept in [file]. */
    constructor(file: File) : this(file, System::currentTimeMillis, { link, existing -> Files.createLink(link, existing) })

    override val description: String
        get() = "key file ${file.absolutePath}"

    /** Null when the file provably does not exist. An existing file that cannot be read throws. */
    override fun read(): String? {
        val path = file.toPath()
        if (Files.notExists(path)) return null
        // Bytes, not readString: a garbled file must read as a malformed key, never as a permanent read failure.
        return Files
            .readAllBytes(path)
            .decodeToString()
            .trim()
            .ifEmpty { null }
    }

    override fun writeIfAbsent(value: String): String {
        val target = targetPath()
        removeStaleTempNames(target)
        if (Files.exists(target)) return existingValue(target)
        val posix = isPosix(target)
        target.parent?.let { folder -> createMissingFolders(folder, posix) }
        val temp = tempPathFor(target)
        val found =
            try {
                writeNewFile(temp, value.encodeToByteArray(), posix)
                putInPlaceUnlessPresent(temp, target)
            } catch (e: Exception) {
                deleteAfterFailure(temp, e)
                throw e
            }
        removeTempName(temp)
        if (found != null) return found
        if (posix) target.parent?.let(::syncFolder)
        return value
    }

    /** Gives [temp] the name [target] unless a file has it already. Null when [temp] is in place; otherwise the value found. */
    private fun putInPlaceUnlessPresent(
        temp: Path,
        target: Path,
    ): String? =
        try {
            createLink(target, temp)
            null
        } catch (_: FileAlreadyExistsException) {
            existingValue(target)
        } catch (_: UnsupportedOperationException) {
            moveUnlessPresent(temp, target)
        } catch (_: FileSystemException) {
            // Some file systems (FAT, some Windows setups) have no hard links and report it only as a generic failure.
            moveUnlessPresent(temp, target)
        }

    /**
     * Without hard links: moves [temp] to [target] under [CREATE_LOCK] unless a file is there by now, never replacing one.
     * Null when moved; otherwise the value found.
     */
    private fun moveUnlessPresent(
        temp: Path,
        target: Path,
    ): String? =
        synchronized(CREATE_LOCK) {
            if (Files.exists(target)) return existingValue(target)
            try {
                moveWithoutReplacing(temp, target)
                null
            } catch (_: FileAlreadyExistsException) {
                existingValue(target)
            }
        }

    /** The value in the existing [target]. Throws when it holds nothing this slot could have written, which is never replaced. */
    private fun existingValue(target: Path): String {
        val stored = Files.readAllBytes(target).decodeToString().trim()
        check(stored.isNotEmpty() && isBase64(stored)) {
            "$description exists but does not hold a readable key — not replacing it. Remove the file to create a new key."
        }
        return stored
    }

    private fun isBase64(text: String): Boolean =
        try {
            Base64.getDecoder().decode(text).fill(0)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * Removes the temp name after the key file got its own, best effort. A leftover is the same owner-only file under a
     * second name, and failing a stored key over it would only make the caller create another.
     */
    private fun removeTempName(temp: Path) {
        runCatching { Files.deleteIfExists(temp) }
    }

    /**
     * Removes this key file's temp names last modified more than [STALE_TEMP_AGE] before [clock], best effort: each is
     * either a crash's leftover, still naming a key, or a failed write's. A younger one may belong to a writer at work.
     */
    private fun removeStaleTempNames(target: Path) {
        val folder = target.parent ?: return
        val prefix = ".${target.fileName}."
        val staleBefore = clock() - STALE_TEMP_AGE.inWholeMilliseconds
        runCatching {
            val temps = Files.newDirectoryStream(folder) { path -> isTempName(path, prefix) }
            temps.use { names -> names.forEach { temp -> deleteIfModifiedBefore(temp, staleBefore) } }
        }
    }

    /**
     * True only for a name of the shape [tempPathFor] gives: [prefix], a UUID in its canonical form, then [TEMP_SUFFIX].
     * A file that merely looks similar was not created here and is left alone.
     */
    private fun isTempName(
        path: Path,
        prefix: String,
    ): Boolean {
        val name = path.fileName.toString()
        if (name.length <= prefix.length + TEMP_SUFFIX.length) return false
        if (!name.startsWith(prefix) || !name.endsWith(TEMP_SUFFIX)) return false
        val middle = name.substring(prefix.length, name.length - TEMP_SUFFIX.length)
        return runCatching { UUID.fromString(middle).toString() == middle }.getOrDefault(false)
    }

    /** Deletes [temp] when it was last modified before [millis] on the wall clock, best effort. */
    private fun deleteIfModifiedBefore(
        temp: Path,
        millis: Long,
    ) {
        runCatching {
            if (Files.getLastModifiedTime(temp, LinkOption.NOFOLLOW_LINKS).toMillis() < millis) Files.deleteIfExists(temp)
        }
    }

    private fun targetPath(): Path = file.toPath().toAbsolutePath()

    private fun isPosix(target: Path): Boolean = POSIX_VIEW in target.fileSystem.supportedFileAttributeViews()

    private fun tempPathFor(target: Path): Path = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}$TEMP_SUFFIX")

    /**
     * Flushes [folder]'s entries to disk, best effort. The key's bytes were forced before the link, but the link itself
     * lives in the folder: a power cut right after the first key is created could lose the file's name, the next boot would
     * see a complete load with no key, and the new key it creates would discard the pairing. Tills get power-cut.
     *
     * POSIX only, since Windows cannot open a folder as a channel. A failure is ignored: the key is already in place.
     */
    private fun syncFolder(folder: Path) {
        runCatching { FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) } }
    }

    /** Creates whichever folders are missing, `rwx------` on POSIX. An existing folder is left exactly as it is. */
    private fun createMissingFolders(
        folder: Path,
        posix: Boolean,
    ) {
        if (posix) Files.createDirectories(folder, OWNER_ONLY_FOLDER) else Files.createDirectories(folder)
    }

    /** Creates [temp], which must not exist yet (`rw-------` from the start on POSIX), and flushes [bytes] to disk. */
    private fun writeNewFile(
        temp: Path,
        bytes: ByteArray,
        posix: Boolean,
    ) {
        val attributes = if (posix) arrayOf<FileAttribute<*>>(OWNER_ONLY_FILE) else emptyArray()
        FileChannel.open(temp, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), *attributes).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun deleteAfterFailure(
        temp: Path,
        failure: Exception,
    ) {
        try {
            Files.deleteIfExists(temp)
        } catch (e: IOException) {
            failure.addSuppressed(e)
        }
    }

    companion object {
        const val FILE_NAME = "local-secrets.key"
        private const val POSIX_VIEW = "posix"
        private const val TEMP_SUFFIX = ".tmp"
        private val OWNER_ONLY_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private val OWNER_ONLY_FOLDER = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        /** How old a temp name must be before [writeIfAbsent] takes it for a leftover rather than a concurrent writer's. */
        private val STALE_TEMP_AGE: Duration = 10.minutes

        /** Serializes [writeIfAbsent] across every slot of this process where the file system has no hard links. */
        private val CREATE_LOCK = Any()

        /**
         * Gives [temp] the name [target], or throws [FileAlreadyExistsException] when a file has that name. No options on
         * purpose: an atomic move is `rename(2)` on POSIX and `MOVEFILE_REPLACE_EXISTING` on Windows, and both replace.
         */
        internal fun moveWithoutReplacing(
            temp: Path,
            target: Path,
        ) {
            Files.move(temp, target)
        }

        /**
         * `%LOCALAPPDATA%\<app>\local-secrets.key` (default `~/AppData/Local`) on Windows, and
         * `$XDG_CONFIG_HOME/<app>/local-secrets.key` (default `~/.config`) elsewhere. Performs no IO.
         *
         * Windows uses the local folder, not the roaming `%APPDATA%`: roaming profiles and folder redirection would copy the
         * key to a server.
         *
         * Deliberately NOT next to the database in `~/.pos/`: copying the database folder must not copy the key with it.
         */
        fun defaultLocation(appName: String = BuildKonfig.APP_NAME): File {
            val folder = appName.lowercase().replace(' ', '-')
            return File(File(configRoot(), folder), FILE_NAME)
        }

        private fun configRoot(): File {
            val home = System.getProperty("user.home")
            val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            return if (isWindows) {
                directoryFromEnv("LOCALAPPDATA") ?: File(home, "AppData/Local")
            } else {
                directoryFromEnv("XDG_CONFIG_HOME") ?: File(home, ".config")
            }
        }

        private fun directoryFromEnv(name: String): File? = System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File)
    }
}
