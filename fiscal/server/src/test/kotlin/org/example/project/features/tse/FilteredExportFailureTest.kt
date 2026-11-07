package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.tse.data.provider.local.FilteredExportFailure
import org.example.project.infrastructure.errors.PosException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real-TSE QA rt12, findings c and the 422: what a failed filtered export leaves and how it reads. */
@DisplayName("A failed filtered TSE export leaves no empty TAR and reports 'no data' as 404")
class FilteredExportFailureTest {
    @Test
    fun `the empty TAR a failed export opened is removed, earlier and non-empty exports are kept`(
        @TempDir dir: File,
    ) {
        val earlierEmpty = File(dir, "tse_export_tx1_to_tx2_20260505_100000.tar").apply { writeBytes(ByteArray(0)) }
        val before = FilteredExportFailure.snapshot(dir.path)
        val leftover = File(dir, "tse_export_tx85_to_tx89_20260506_120000.tar").apply { writeBytes(ByteArray(0)) }
        val concurrentDeliverable = File(dir, "tse_export_20260506_to_20260506_20260506_120001.tar").apply { writeBytes(ByteArray(512)) }
        val unrelated = File(dir, "notes.tar").apply { writeBytes(ByteArray(0)) }

        val removed = FilteredExportFailure.removeEmptyLeftovers(dir.path, before)

        assertEquals(listOf(leftover.name), removed.map { it.name })
        assertFalse(leftover.exists())
        assertTrue(earlierEmpty.exists(), "a file that predates this export is not this failure's debris")
        assertTrue(concurrentDeliverable.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun `the device's no-data answer becomes a 404`() {
        val rejected =
            PosException(
                PosErrorCode.TSE_OPERATION_REJECTED,
                HttpStatusCode.UnprocessableEntity,
                "TSE rejected the operation: ${FilteredExportFailure.NO_DATA_ERROR}",
            )

        val mapped = FilteredExportFailure.noDataAsNotFound(rejected, "TANR 85–89")

        assertEquals(PosErrorCode.TSE_TRANSACTION_NOT_FOUND, mapped.code)
        assertEquals(HttpStatusCode.NotFound, mapped.httpStatus)
        assertTrue(mapped.message!!.contains("TANR 85–89"))
    }

    @Test
    fun `any other failure passes through unchanged`() {
        val otherRejection = PosException(PosErrorCode.TSE_OPERATION_REJECTED, HttpStatusCode.UnprocessableEntity, "TSE rejected the operation: WORM_ERROR_X")
        val unreachable = PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, FilteredExportFailure.NO_DATA_ERROR)

        assertSame(otherRejection, FilteredExportFailure.noDataAsNotFound(otherRejection, "x"))
        assertSame(unreachable, FilteredExportFailure.noDataAsNotFound(unreachable, "x"))
    }
}
