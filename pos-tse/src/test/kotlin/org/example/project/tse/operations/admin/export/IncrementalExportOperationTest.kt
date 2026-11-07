package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarIncrementalCallback
import com.secureflashcard.wormapi.WormExportTarIncrementalResponse
import com.secureflashcard.wormapi.WormStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.operations.admin.NativeInfoHandle
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F40 / RT2-B2: the operation opens its `.tar` before asking the TSE for data, so an export that
 * produced nothing left a 0-byte file in the export folder while reporting `filePath = null`.
 */
class IncrementalExportOperationTest {

    @TempDir
    lateinit var exportDir: File

    private val lastState = byteArrayOf(0, 0, 0, 100)

    private fun storeThat(answer: (WormExportTarIncrementalCallback) -> WormExportTarIncrementalResponse): WormStore =
        mockk {
            every { export_tar_incremental_ex(any(), any(), any()) } answers { answer(thirdArg()) }
        }

    private fun run(store: WormStore) =
        IncrementalExportOperation(adminPin = "12345", outputPath = exportDir.absolutePath, lastState = lastState)
            .execute(NativeInfoHandle.tseStatusFor(store))

    @Test
    fun `no new data leaves no file behind and keeps the connection`() {
        val store = storeThat { throw WormException(WormError.WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA.swigValue()) }
        val tseStatus = NativeInfoHandle.tseStatusFor(store)

        val result = IncrementalExportOperation(adminPin = "12345", outputPath = exportDir.absolutePath, lastState = lastState)
            .execute(tseStatus)

        assertTrue(result.success)
        assertNull(result.filePath)
        assertEquals(0L, result.bytesExported)
        assertEquals(Base64.getEncoder().encodeToString(lastState), result.newState, "the state is echoed unchanged")
        assertEquals(emptyList(), exportDir.listFiles()!!.map { it.name }, "no empty .tar may remain")
        verify(exactly = 0) { tseStatus.handleHardwareError(any()) }
    }

    @Test
    fun `a failed export removes its partial file`() {
        val store = storeThat { callback ->
            callback.onNewData(ByteArray(512) { 1 }, 1, 4)
            throw WormException(WormError.WORM_ERROR_EXPORT_FAILED.swigValue())
        }

        assertFailsWith<PosException> { run(store) }

        assertEquals(emptyList(), exportDir.listFiles()!!.map { it.name }, "no partial .tar may remain")
    }

    @Test
    fun `a successful export keeps its file`() {
        val response = mockk<WormExportTarIncrementalResponse> {
            every { allDataExported() } returns true
            every { newState() } returns byteArrayOf(9)
        }
        val store = storeThat { callback ->
            callback.onNewData(ByteArray(1024) { 2 }, 2, 2)
            response
        }

        val result = run(store)

        val file = File(result.filePath!!)
        assertTrue(file.exists())
        assertEquals(1024L, file.length())
        assertEquals(listOf(file.name), exportDir.listFiles()!!.map { it.name })
    }
}
