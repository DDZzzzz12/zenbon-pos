package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormStore
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.example.project.tse.operations.admin.NativeInfoHandle
import org.example.project.tse.utils.FilteredTarExport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test

/**
 * F69: the filtered exports pass the requested client, or the "all clients" value when none (or
 * a blank one) is requested — never the configured `TseStatus.getClientId()`.
 *
 * TSE 1.x: the SDK javadoc for `export_tar_filtered_time` / `export_tar_filtered_transaction`
 * says "Use an empty string to include transactions of all clients" (and its SWIG wrapper rejects
 * null). TSE 2.0: the hardware filter is gone, and [FilteredTarExport] treats `null` as "no client
 * filter". In every case below the till IS configured as TER-001, which is what used to leak in.
 */
class FilteredExportClientArgumentTest {

    @AfterEach
    fun tearDown() = unmockkObject(FilteredTarExport)

    private fun store(isV2: Boolean): WormStore {
        val handle = NativeInfoHandle { every { isTSEv2() } returns isV2 }
        return mockk(relaxed = true) {
            every { info() } returns handle.info
            every { export_tar(any()) } just runs
            every { export_tar_filtered_time(any(), any(), any(), any()) } just runs
            every { export_tar_filtered_transaction(any(), any(), any(), any()) } just runs
        }
    }

    private fun tseStatusConfiguredAsTer001(store: WormStore) =
        NativeInfoHandle.tseStatusFor(store).also { every { it.getClientId() } returns "TER-001" }

    @Test
    fun `TSE 1x time-filtered export asks the hardware for all clients`(@TempDir dir: File) {
        val store = store(isV2 = false)

        TimeFilteredExportOperation("", dir.absolutePath, 1_000_000L, 2_000_000L)
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { store.export_tar_filtered_time(1_000L, 2_000L, "", any()) }
        verify(exactly = 0) { store.export_tar_filtered_time(any(), any(), "TER-001", any()) }
    }

    @Test
    fun `TSE 1x transaction-filtered export asks the hardware for all clients`(@TempDir dir: File) {
        val store = store(isV2 = false)

        TransactionFilteredExportOperation("", dir.absolutePath, 5L, 9L)
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { store.export_tar_filtered_transaction(5L, 9L, "", any()) }
        verify(exactly = 0) { store.export_tar_filtered_transaction(any(), any(), "TER-001", any()) }
    }

    @Test
    fun `TSE 2_0 time-filtered export filters the TAR for all clients`(@TempDir dir: File) {
        mockkObject(FilteredTarExport)
        every { FilteredTarExport.byTime(any(), any(), any(), any()) } returns ByteArray(0)
        val store = store(isV2 = true)

        TimeFilteredExportOperation("", dir.absolutePath, 1_000_000L, 2_000_000L)
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { FilteredTarExport.byTime(any(), 1_000L, 2_000L, null) }
        verify(exactly = 0) { FilteredTarExport.byTime(any(), any(), any(), "TER-001") }
    }

    @Test
    fun `TSE 2_0 transaction-filtered export filters the TAR for all clients`(@TempDir dir: File) {
        mockkObject(FilteredTarExport)
        every { FilteredTarExport.byTransactionNumber(any(), any(), any(), any()) } returns ByteArray(0)
        val store = store(isV2 = true)

        TransactionFilteredExportOperation("", dir.absolutePath, 5L, 9L)
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { FilteredTarExport.byTransactionNumber(any(), 5L, 9L, null) }
        verify(exactly = 0) { FilteredTarExport.byTransactionNumber(any(), any(), any(), "TER-001") }
    }

    @Test
    fun `TSE 1x blank client is all clients`(@TempDir dir: File) {
        val store = store(isV2 = false)

        TimeFilteredExportOperation("", dir.absolutePath, 1_000_000L, 2_000_000L, clientId = "  ")
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { store.export_tar_filtered_time(1_000L, 2_000L, "", any()) }
    }

    @Test
    fun `TSE 1x requested client is passed to the hardware filter`(@TempDir dir: File) {
        val store = store(isV2 = false)
        val status = tseStatusConfiguredAsTer001(store)

        TimeFilteredExportOperation("", dir.absolutePath, 1_000_000L, 2_000_000L, clientId = "TER-002").execute(status)
        TransactionFilteredExportOperation("", dir.absolutePath, 5L, 9L, clientId = "TER-002").execute(status)

        verify(exactly = 1) { store.export_tar_filtered_time(1_000L, 2_000L, "TER-002", any()) }
        verify(exactly = 1) { store.export_tar_filtered_transaction(5L, 9L, "TER-002", any()) }
    }

    @Test
    fun `TSE 2_0 requested client is passed to the software filter`(@TempDir dir: File) {
        mockkObject(FilteredTarExport)
        every { FilteredTarExport.byTime(any(), any(), any(), any()) } returns ByteArray(0)
        every { FilteredTarExport.byTransactionNumber(any(), any(), any(), any()) } returns ByteArray(0)
        val store = store(isV2 = true)
        val status = tseStatusConfiguredAsTer001(store)

        TimeFilteredExportOperation("", dir.absolutePath, 1_000_000L, 2_000_000L, clientId = "TER-002").execute(status)
        TransactionFilteredExportOperation("", dir.absolutePath, 5L, 9L, clientId = "TER-002").execute(status)

        verify(exactly = 1) { FilteredTarExport.byTime(any(), 1_000L, 2_000L, "TER-002") }
        verify(exactly = 1) { FilteredTarExport.byTransactionNumber(any(), 5L, 9L, "TER-002") }
    }

    @Test
    fun `TSE 2_0 blank client is all clients`(@TempDir dir: File) {
        mockkObject(FilteredTarExport)
        every { FilteredTarExport.byTransactionNumber(any(), any(), any(), any()) } returns ByteArray(0)
        val store = store(isV2 = true)

        TransactionFilteredExportOperation("", dir.absolutePath, 5L, 9L, clientId = "")
            .execute(tseStatusConfiguredAsTer001(store))

        verify(exactly = 1) { FilteredTarExport.byTransactionNumber(any(), 5L, 9L, null) }
    }
}
