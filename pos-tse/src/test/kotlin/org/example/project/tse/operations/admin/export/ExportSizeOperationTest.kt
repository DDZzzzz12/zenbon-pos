package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.NativeInfoHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: the operation read `tarExportSizeInSectors()` from a WormInformation handle it had
 * already closed in a previous `use {}` block. On a real Swissbit TSE that is a use-after-free in
 * the JNI library and it SIGSEGV'd the server JVM.
 */
class ExportSizeOperationTest {

    @Test
    fun `reads both full-export figures from one live info handle and closes it once`() {
        lateinit var handle: NativeInfoHandle
        handle = NativeInfoHandle {
            every { tarExportSize() } answers { handle.live(4_096_000L) }
            every { tarExportSizeInSectors() } answers { handle.live(8_000L) }
        }
        val store = mockk<WormStore> {
            every { info() } returns handle.info
            every { export_tar_incremental_size(null) } returns 512L
            every { export_tar_incremental_sizeInSectors(null) } returns 1L
        }

        val result = ExportSizeOperation(adminPin = "12345").execute(NativeInfoHandle.tseStatusFor(store))

        assertEquals(0, handle.accessAfterClose, "no getter may run on a closed native handle")
        assertEquals(1, handle.closeCount, "the info handle must be released exactly once")
        assertEquals(4_096_000L, result.fullExportSizeBytes)
        assertEquals(8_000L, result.fullExportSizeSectors)
        assertEquals(512L, result.incrementalExportSizeBytes)
        assertEquals(1L, result.incrementalExportSizeSectors)
        assertFalse(result.hasStateForIncremental)
    }

    /**
     * F40 / RT2-B1: asked with the state an incremental export just returned, a real Swissbit TSE
     * answers WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA. That is "nothing new", so size 0 — it used to
     * surface as 503 "Failed to connect to TSE hardware" and reset the connection.
     */
    @Test
    fun `no new data since lastState is an incremental size of zero and keeps the connection`() {
        val lastState = byteArrayOf(0, 0, 0, 100)
        val noData = WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA.swigValue()
        lateinit var handle: NativeInfoHandle
        handle = NativeInfoHandle {
            every { tarExportSize() } answers { handle.live(53_248L) }
            every { tarExportSizeInSectors() } answers { handle.live(104L) }
        }
        val store = mockk<WormStore> {
            every { info() } returns handle.info
            every { export_tar_incremental_size(lastState) } throws WormException(noData)
            every { export_tar_incremental_sizeInSectors(lastState) } throws WormException(noData)
        }
        val tseStatus = NativeInfoHandle.tseStatusFor(store)

        val result = ExportSizeOperation(adminPin = "12345", lastState = lastState).execute(tseStatus)

        assertEquals(0L, result.incrementalExportSizeBytes)
        assertEquals(0L, result.incrementalExportSizeSectors)
        assertEquals(53_248L, result.fullExportSizeBytes)
        assertTrue(result.hasStateForIncremental)
        verify(exactly = 0) { tseStatus.handleHardwareError(any()) }
    }

    @Test
    fun `any other incremental size failure still fails the call`() {
        val lastState = byteArrayOf(1)
        val handle = NativeInfoHandle {
            every { tarExportSize() } returns 1L
            every { tarExportSizeInSectors() } returns 1L
        }
        val store = mockk<WormStore> {
            every { info() } returns handle.info
            every { export_tar_incremental_size(lastState) } throws
                WormException(WormError.WORM_ERROR_STORE_FULL.swigValue())
        }

        val e = assertFailsWith<PosException> {
            ExportSizeOperation(adminPin = "12345", lastState = lastState).execute(NativeInfoHandle.tseStatusFor(store))
        }
        assertEquals(PosErrorCode.TSE_OPERATION_REJECTED, e.code)
    }
}
