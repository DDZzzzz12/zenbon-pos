package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormInformation
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.NativeInfoHandle
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * rt13 A4 / F68: a delete that logged Admin in AFTER a separate export could never succeed on a
 * real Swissbit TSE — the login is a new log entry, so the device answered
 * `WORM_ERROR_EXPORT_UNACKNOWLEDGED_DATA`. The operation is now login → export → written file →
 * delete → logout in one sitting, and deletes nothing unless the archive is on disk.
 *
 * The virtual TSE does not model the "unexported data" rule, so these tests pin the ORDER with a
 * mocked store instead of reproducing the device refusal.
 */
class DeleteExportedDataOperationTest {

    @TempDir
    lateinit var exportDir: File

    private val tar = ByteArray(3 * 512) { (it % 7).toByte() }

    private fun info(exportSize: Long = tar.size.toLong(), v2: Boolean = false, validTime: Boolean = true) =
        NativeInfoHandle {
            every { size() } returns 42L
            every { tarExportSize() } returns exportSize
            every { isTSEv2() } returns v2
            every { hasValidTime() } returns validTime
        }.info

    private fun store(
        exportAnswer: (WormExportTarCallback) -> Unit = { it.onNewData(tar) },
        infoFactory: () -> WormInformation = { info() },
    ): WormStore =
        mockk(relaxed = true) {
            every { info() } answers { infoFactory() }
            every { export_tar(any()) } answers { exportAnswer(firstArg()) }
        }

    private fun run(store: WormStore, dir: File = exportDir) =
        DeleteExportedDataOperation(adminPin = "12345", outputPath = dir.absolutePath)
            .execute(NativeInfoHandle.tseStatusFor(store))

    @Test
    fun `logs in, exports, writes the file, deletes, then logs out - in that order`() {
        val store = store()

        val result = run(store)

        verifyOrder {
            store.user_login(WormUserId.WORM_USER_ADMIN, any())
            store.export_tar(any())
            store.export_deleteStoredData()
            store.user_logout(WormUserId.WORM_USER_ADMIN)
        }
        val file = File(result.archiveFilePath)
        assertTrue(file.exists(), "the archive must survive the delete")
        assertEquals(tar.size.toLong(), file.length())
        assertEquals(file.name, result.archiveFileName)
        assertEquals(tar.size.toLong(), result.archiveSizeBytes)
        assertEquals(42L, result.usedBlocksBefore)
        assertTrue(result.success)
    }

    @Test
    fun `the archive is fully on disk at the moment the device is asked to delete`() {
        var sizeAtDelete = -1L
        val store = store()
        every { store.export_deleteStoredData() } answers {
            sizeAtDelete = exportDir.listFiles()!!.single().length()
        }

        run(store)

        assertEquals(tar.size.toLong(), sizeAtDelete)
    }

    @Test
    fun `a failed export deletes nothing, leaves no partial file and still logs out`() {
        val store = store(exportAnswer = {
            it.onNewData(ByteArray(512))
            throw WormException(WormError.WORM_ERROR_EXPORT_FAILED.swigValue())
        })

        val e = assertFailsWith<PosException> { run(store) }

        assertTrue(e.message!!.contains("step EXPORT"), e.message)
        verify(exactly = 0) { store.export_deleteStoredData() }
        verify { store.user_logout(WormUserId.WORM_USER_ADMIN) }
        assertEquals(emptyList(), exportDir.listFiles()!!.map { it.name })
        Unit
    }

    @Test
    fun `a short archive fails verification and deletes nothing`() {
        val store = store(infoFactory = { info(exportSize = tar.size + 512L) })

        val e = assertFailsWith<PosException> { run(store) }

        assertEquals(PosErrorCode.TSE_EXPORT_FAILED, e.code)
        assertTrue(e.message!!.contains("step VERIFY"), e.message)
        verify(exactly = 0) { store.export_deleteStoredData() }
        assertEquals(emptyList(), exportDir.listFiles()!!.map { it.name })
        Unit
    }

    @Test
    fun `an empty export deletes nothing`() {
        val store = store(exportAnswer = {}, infoFactory = { info(exportSize = 0) })

        assertFailsWith<PosException> { run(store) }

        verify(exactly = 0) { store.export_deleteStoredData() }
        Unit
    }

    @Test
    fun `an unwritable export directory is refused before the export`() {
        val notADirectory = File(exportDir, "plain-file").apply { writeText("x") }
        val store = store()

        val e = assertFailsWith<PosException> { run(store, dir = File(notADirectory, "tse")) }

        assertTrue(e.message!!.contains("step PRECHECK"), e.message)
        verify(exactly = 0) { store.export_tar(any()) }
        verify(exactly = 0) { store.export_deleteStoredData() }
        Unit
    }

    @Test
    fun `a TSE 2_0 without a valid time is refused before the export`() {
        val store = store(infoFactory = { info(v2 = true, validTime = false) })

        val e = assertFailsWith<PosException> { run(store) }

        assertEquals(PosErrorCode.TSE_TIME_SYNC_REQUIRED, e.code)
        verify(exactly = 0) { store.export_tar(any()) }
        verify(exactly = 0) { store.export_deleteStoredData() }
        Unit
    }

    @Test
    fun `a failed login exports and deletes nothing`() {
        val store = store()
        every { store.user_login(any(), any()) } throws
            WormException(WormError.WORM_ERROR_AUTHENTICATION_FAILED.swigValue())

        val e = assertFailsWith<PosException> { run(store) }

        assertTrue(e.message!!.contains("step LOGIN"), e.message)
        verify(exactly = 0) { store.export_tar(any()) }
        verify(exactly = 0) { store.export_deleteStoredData() }
        Unit
    }

    @Test
    fun `a device refusal of the delete keeps the archive and names the step`() {
        val store = store()
        every { store.export_deleteStoredData() } throws
            WormException(WormError.WORM_ERROR_EXPORT_UNACKNOWLEDGED_DATA.swigValue())

        val e = assertFailsWith<PosException> { run(store) }

        assertTrue(e.message!!.contains("step DELETE"), e.message)
        assertEquals(1, exportDir.listFiles()!!.size, "the written archive is kept")
        verify { store.user_logout(WormUserId.WORM_USER_ADMIN) }
        Unit
    }
}
