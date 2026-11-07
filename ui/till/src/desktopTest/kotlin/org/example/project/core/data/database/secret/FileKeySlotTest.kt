package org.example.project.core.data.database.secret

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FileKeySlotTest {
    private val dir: Path = createTempDirectory("file-key-slot-test")

    @AfterTest
    fun deleteTempDir() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun a_missing_file_reads_as_absent() {
        val slot = FileKeySlot(dir.resolve("missing/local-secrets.key").toFile())

        assertNull(slot.read())
    }

    @Test
    fun a_created_key_reads_back_and_creates_parent_folders() {
        val file = dir.resolve("config/pos/local-secrets.key")
        val slot = FileKeySlot(file.toFile())

        slot.writeIfAbsent("c2VjcmV0LWtleQ==")

        assertTrue(Files.isDirectory(file.parent))
        assertEquals("c2VjcmV0LWtleQ==", slot.read())
        assertEquals("c2VjcmV0LWtleQ==", FileKeySlot(file.toFile()).read())
    }

    @Test
    fun the_key_file_offers_no_write_that_replaces_its_key() {
        val slot: KeySlot = FileKeySlot(dir.resolve("pos/local-secrets.key").toFile())

        assertFalse(slot is ReplaceableKeySlot)
    }

    @Test
    fun an_existing_folder_keeps_its_permissions() {
        if (!supportsPosix()) return
        val folder = Files.createDirectory(dir.resolve("pos"))
        val shared = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(folder, shared)

        FileKeySlot(folder.resolve("local-secrets.key").toFile()).writeIfAbsent("c2VjcmV0LWtleQ==")

        assertEquals(shared, Files.getPosixFilePermissions(folder))
    }

    @Test
    fun a_failed_create_leaves_no_temp_file_behind() {
        val folder = Files.createDirectory(dir.resolve("pos"))
        val target = Files.createDirectory(folder.resolve("local-secrets.key"))
        Files.writeString(target.resolve("occupant"), "not a key")

        assertFailsWith<IOException> { FileKeySlot(target.toFile()).writeIfAbsent("c2VjcmV0LWtleQ==") }

        assertEquals(listOf("local-secrets.key"), filesIn(folder))
    }

    @Test
    fun write_if_absent_creates_the_key_with_owner_only_permissions() {
        val file = dir.resolve("config/pos/local-secrets.key")

        val stored = FileKeySlot(file.toFile()).writeIfAbsent(KEY_A)

        assertEquals(KEY_A, stored)
        assertEquals(KEY_A, FileKeySlot(file.toFile()).read())
        if (supportsPosix()) {
            assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(file))
            assertEquals(OWNER_ONLY_FOLDER, Files.getPosixFilePermissions(file.parent))
        }
    }

    @Test
    fun write_if_absent_returns_the_existing_key_and_does_not_overwrite_it() {
        val file = dir.resolve("pos/local-secrets.key")
        FileKeySlot(file.toFile()).writeIfAbsent(KEY_A)

        val stored = FileKeySlot(file.toFile()).writeIfAbsent(KEY_B)

        assertEquals(KEY_A, stored)
        assertEquals(KEY_A, FileKeySlot(file.toFile()).read())
    }

    @Test
    fun write_if_absent_refuses_to_overwrite_a_garbled_file() {
        val file = Files.createDirectory(dir.resolve("pos")).resolve("local-secrets.key")
        for (garbled in listOf("not base64 !!", "", "  \n")) {
            Files.writeString(file, garbled)

            val thrown = assertFailsWith<IllegalStateException> { FileKeySlot(file.toFile()).writeIfAbsent(KEY_A) }

            assertEquals(garbled, Files.readString(file))
            assertFalse(KEY_A in thrown.message.orEmpty(), thrown.message)
        }
    }

    @Test
    fun write_if_absent_leaves_no_temp_file_behind() {
        val file = dir.resolve("pos/local-secrets.key")
        val onlyTheKeyFile = listOf(file.fileName.toString())

        FileKeySlot(file.toFile()).writeIfAbsent(KEY_A)
        assertEquals(onlyTheKeyFile, filesIn(file.parent))
        FileKeySlot(file.toFile()).writeIfAbsent(KEY_B)
        assertEquals(onlyTheKeyFile, filesIn(file.parent))
        Files.delete(file)
        val anotherProcessFirst = FileKeySlot(file.toFile()) { link, existing -> anotherProcessStores(KEY_B, link, existing) }

        assertEquals(KEY_B, anotherProcessFirst.writeIfAbsent(KEY_A))
        assertEquals(onlyTheKeyFile, filesIn(file.parent))
        assertEquals(KEY_B, FileKeySlot(file.toFile()).read())
    }

    @Test
    fun without_hard_links_a_key_file_another_process_stored_first_is_returned_and_left_unchanged() {
        val file = Files.createDirectory(dir.resolve("pos")).resolve("local-secrets.key")
        val noHardLinks =
            FileKeySlot(file.toFile()) { link, _ ->
                Files.writeString(link, KEY_B)
                throw FileSystemException(link.toString(), null, "Incorrect function")
            }

        val stored = noHardLinks.writeIfAbsent(KEY_A)

        assertEquals(KEY_B, stored)
        assertEquals(KEY_B, Files.readString(file))
        assertEquals(listOf(file.fileName.toString()), filesIn(file.parent))
    }

    @Test
    fun the_move_used_without_hard_links_never_replaces_an_existing_key_file() {
        val folder = Files.createDirectory(dir.resolve("pos"))
        val target = Files.writeString(folder.resolve("local-secrets.key"), KEY_B)
        val temp = Files.writeString(folder.resolve(".local-secrets.key.${UUID.randomUUID()}.tmp"), KEY_A)

        assertFailsWith<FileAlreadyExistsException> { FileKeySlot.moveWithoutReplacing(temp, target) }

        assertEquals(KEY_B, Files.readString(target))
        assertEquals(KEY_A, Files.readString(temp))
    }

    @Test
    fun write_if_absent_removes_temp_names_left_by_a_crash_but_not_ones_a_writer_may_still_use() {
        val folder = Files.createDirectory(dir.resolve("pos"))
        val file = folder.resolve("local-secrets.key")
        val now = System.currentTimeMillis()
        val leftByACrash = fileModifiedAt(folder.resolve(".local-secrets.key.${UUID.randomUUID()}.tmp"), now - 11.minutes.inWholeMilliseconds)
        val stillBeingWritten = fileModifiedAt(folder.resolve(".local-secrets.key.${UUID.randomUUID()}.tmp"), now - 9.minutes.inWholeMilliseconds)
        val unrelated = fileModifiedAt(folder.resolve("notes.tmp"), now - 60.minutes.inWholeMilliseconds)
        val slot = FileKeySlot(file.toFile(), clock = { now }) { link, existing -> Files.createLink(link, existing) }

        slot.writeIfAbsent(KEY_A)

        assertFalse(Files.exists(leftByACrash))
        assertTrue(Files.exists(stillBeingWritten))
        assertTrue(Files.exists(unrelated))
        assertEquals(KEY_A, slot.read())
    }

    @Test
    fun write_if_absent_keeps_old_names_that_only_look_like_its_temp_names() {
        val folder = Files.createDirectory(dir.resolve("pos"))
        val now = System.currentTimeMillis()
        val old = now - 60.minutes.inWholeMilliseconds
        val leftByACrash = fileModifiedAt(folder.resolve(".local-secrets.key.${UUID.randomUUID()}.tmp"), old)
        val lookAlikes =
            listOf(".local-secrets.key.not-a-uuid.tmp", ".local-secrets.key.1-1-1-1-1.tmp", ".local-secrets.key.tmp")
                .map { name -> fileModifiedAt(folder.resolve(name), old) }
        val slot = FileKeySlot(folder.resolve("local-secrets.key").toFile(), clock = { now }) { link, existing -> Files.createLink(link, existing) }

        slot.writeIfAbsent(KEY_A)

        assertFalse(Files.exists(leftByACrash))
        lookAlikes.forEach { lookAlike -> assertTrue(Files.exists(lookAlike), "$lookAlike was removed") }
    }

    @Test
    fun concurrent_write_if_absent_stores_exactly_one_key() {
        val file = dir.resolve("pos/local-secrets.key")

        val stored = storeConcurrently { FileKeySlot(file.toFile()) }

        assertExactlyOneKeyStored(stored, file)
    }

    @Test
    fun without_hard_links_concurrent_write_if_absent_still_stores_exactly_one_key() {
        val failures =
            mapOf<String, () -> Exception>(
                "unsupported" to { UnsupportedOperationException("hard links") },
                "no-links-fs" to { FileSystemException("local-secrets.key", null, "Incorrect function") },
            )
        for ((name, failure) in failures) {
            val file = dir.resolve("$name/local-secrets.key")

            val stored = storeConcurrently { FileKeySlot(file.toFile()) { _, _ -> throw failure() } }

            assertExactlyOneKeyStored(stored, file)
        }
    }

    /** [THREADS] threads, released together, each offering its own key through its own slot; the values they got back. */
    private fun storeConcurrently(slot: () -> FileKeySlot): List<String> {
        val start = CountDownLatch(1)
        val stored = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers =
            List(THREADS) { index ->
                thread {
                    try {
                        start.await()
                        stored += slot().writeIfAbsent(keyNumber(index))
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertTrue(failures.isEmpty(), "Concurrent writes failed: $failures")
        return stored.toList()
    }

    private fun assertExactlyOneKeyStored(
        stored: List<String>,
        file: Path,
    ) {
        assertEquals(THREADS, stored.size)
        assertEquals(1, stored.toSet().size, "$stored")
        assertTrue(stored.first() in List(THREADS, ::keyNumber))
        assertEquals(stored.first(), FileKeySlot(file.toFile()).read())
        assertEquals(listOf(file.fileName.toString()), filesIn(file.parent))
    }

    /** Stores [value] at [link] the way another process would, just before this one links [existing] there. */
    private fun anotherProcessStores(
        value: String,
        link: Path,
        existing: Path,
    ) {
        Files.writeString(link, value)
        Files.createLink(link, existing)
    }

    /** Creates [path] holding [KEY_B], last modified at [millis] on the wall clock. */
    private fun fileModifiedAt(
        path: Path,
        millis: Long,
    ): Path = Files.writeString(path, KEY_B).also { Files.setLastModifiedTime(it, FileTime.fromMillis(millis)) }

    private fun keyNumber(index: Int): String = Base64.getEncoder().encodeToString(ByteArray(DesktopMasterKey.KEY_BYTES) { index.toByte() })

    private fun filesIn(folder: Path): List<String> = Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList() }

    private fun supportsPosix(): Boolean = "posix" in dir.fileSystem.supportedFileAttributeViews()

    private companion object {
        const val KEY_A = "c2VjcmV0LWtleQ=="
        const val KEY_B = "bmV3LWtleQ=="
        const val THREADS = 8
        val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val OWNER_ONLY_FOLDER = PosixFilePermissions.fromString("rwx------")
    }
}
