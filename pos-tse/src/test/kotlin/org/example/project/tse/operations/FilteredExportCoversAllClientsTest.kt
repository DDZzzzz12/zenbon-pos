package org.example.project.tse.operations

import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormUserId
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.export.TimeFilteredExportOperation
import org.example.project.tse.operations.admin.export.TransactionFilteredExportOperation
import org.example.project.tse.utils.FilteredTarExport
import org.example.project.tse.utils.Tr03153LogMessageParser
import org.example.project.tse.utils.UstarArchive
import org.example.project.tse.virtual.VirtualTseTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals

/**
 * F69: with no client requested, a filtered export is an audit extract of the **whole TSE**.
 *
 * Both filtered exports used to pass the configured client ID into the filter. On a real Swissbit
 * TSE that answered a time-range export with zero transactions, and a TANR-range export with "no
 * data" for TANRs that exist — they were signed under another client. The SDK documents the empty
 * string as "all clients" (TSE 1.x hardware filter) and pos-tse's own TSE 2.0 software filter
 * treats `null` the same way.
 *
 * Run end to end against the virtual device, which honours the client filter on both generations
 * (v1 in its hardware filter, v2 through [FilteredTarExport]). The configured client is TER-001;
 * the second transaction is signed under TER-002 and must still come out — unless a client is
 * requested, in which case exactly that client's transactions come out.
 */
class FilteredExportCoversAllClientsTest {

    private val tseStatus = TseStatus()

    @AfterEach
    fun tearDown() {
        tseStatus.close()
        VirtualTseTestSupport.detachDevice()
    }

    /** Commissions a device with two clients and one transaction each; returns their TANRs. */
    private fun twoClientsWithOneTransactionEach(generation: String): List<Long> {
        VirtualTseTestSupport.attachDevice(generation)
        tseStatus.setMountPoint(VirtualTseTestSupport.MOUNT_POINT)
        tseStatus.setMode(TseMode.NORMAL)
        tseStatus.setTimeAdminPin("98765")
        check(tseStatus.detectHardware())
        val store = tseStatus.getWormStore()
        store.tse_setup_ext(
            "SwissbitSwissbit".toByteArray(), "888888".toByteArray(),
            "54321".toByteArray(), "98765".toByteArray(), "TER-001",
        )
        store.tse_updateTime(System.currentTimeMillis() / 1000)
        store.user_login(WormUserId.WORM_USER_ADMIN, "54321".toByteArray())
        store.tse_registerClient("TER-002")
        runCatching { store.user_logout(WormUserId.WORM_USER_ADMIN) }

        val first = store.transaction_start("TER-001", "a".toByteArray(), "Kassenbeleg-V1")
        val second = store.transaction_start("TER-002", "b".toByteArray(), "Kassenbeleg-V1")
        // The till is configured as TER-001 — the shape that hid TER-002's transaction.
        tseStatus.setClientId("TER-001")
        return listOf(first.transactionNumber(), second.transactionNumber()).sorted()
    }

    /** (transactionNumber, clientId) of every transaction log message in [tar], sorted. */
    private fun transactionsIn(tar: ByteArray): List<Pair<Long, String?>> =
        UstarArchive.read(tar)
            .mapNotNull { Tr03153LogMessageParser.parse(it.content) }
            .mapNotNull { parsed -> parsed.transactionNumber?.let { it to parsed.clientId } }
            .sortedBy { it.first }

    @ParameterizedTest
    @ValueSource(strings = ["v1", "v2"])
    fun `a transaction-filtered export returns every client's transactions`(generation: String, @TempDir dir: File) {
        val tanrs = twoClientsWithOneTransactionEach(generation)

        val result = TransactionFilteredExportOperation(
            adminPin = "",
            outputPath = dir.absolutePath,
            startTransaction = tanrs.first(),
            endTransaction = tanrs.last(),
        ).execute(tseStatus)

        assertEquals(
            listOf(tanrs[0] to "TER-001", tanrs[1] to "TER-002"),
            transactionsIn(File(result.filePath).readBytes()),
            "both clients' transactions must be in the export, not only the configured TER-001",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["v1", "v2"])
    fun `a time-filtered export returns every client's transactions`(generation: String, @TempDir dir: File) {
        val tanrs = twoClientsWithOneTransactionEach(generation)
        val nowMs = System.currentTimeMillis()

        val result = TimeFilteredExportOperation(
            adminPin = "",
            outputPath = dir.absolutePath,
            startDate = nowMs - 3_600_000,
            endDate = nowMs + 60_000,
        ).execute(tseStatus)

        assertEquals(
            listOf(tanrs[0] to "TER-001", tanrs[1] to "TER-002"),
            transactionsIn(File(result.filePath).readBytes()),
            "both clients' transactions must be in the export, not only the configured TER-001",
        )
    }

    /**
     * A requested client filters on exactly that client — and doubles as the negative control:
     * the fixture discriminates, so the all-clients tests above cannot pass vacuously. TER-002 is
     * deliberately NOT the configured client, proving the operation uses the request, not TseStatus.
     */
    @ParameterizedTest
    @ValueSource(strings = ["v1", "v2"])
    fun `a requested client gets exactly that client's transactions`(generation: String, @TempDir dir: File) {
        val tanrs = twoClientsWithOneTransactionEach(generation)
        val nowMs = System.currentTimeMillis()

        val byTx = TransactionFilteredExportOperation(
            adminPin = "",
            outputPath = dir.absolutePath,
            startTransaction = tanrs.first(),
            endTransaction = tanrs.last(),
            clientId = "TER-002",
        ).execute(tseStatus)
        val byTime = TimeFilteredExportOperation(
            adminPin = "",
            outputPath = dir.absolutePath,
            startDate = nowMs - 3_600_000,
            endDate = nowMs + 60_000,
            clientId = "TER-002",
        ).execute(tseStatus)

        assertEquals(listOf(tanrs[1] to "TER-002"), transactionsIn(File(byTx.filePath).readBytes()))
        assertEquals(listOf(tanrs[1] to "TER-002"), transactionsIn(File(byTime.filePath).readBytes()))
    }

    /** The raw filter itself: the configured client alone drops the other one (old behaviour). */
    @ParameterizedTest
    @ValueSource(strings = ["v1", "v2"])
    fun `filtering for the configured client alone drops the other client`(generation: String) {
        val tanrs = twoClientsWithOneTransactionEach(generation)
        val store = tseStatus.getWormStore()

        val singleClient = if (generation == "v1") {
            collect { store.export_tar_filtered_transaction(tanrs.first(), tanrs.last(), "TER-001", it) }
        } else {
            FilteredTarExport.byTransactionNumber(
                collect { store.export_tar(it) },
                tanrs.first(),
                tanrs.last(),
                "TER-001",
            )
        }

        assertEquals(listOf(tanrs[0] to "TER-001"), transactionsIn(singleClient))
    }

    private fun collect(export: (WormExportTarCallback) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        export(object : WormExportTarCallback() {
            override fun onNewData(data: ByteArray): Int {
                out.write(data)
                return 0
            }
        })
        return out.toByteArray()
    }
}
