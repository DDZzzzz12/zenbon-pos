package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormStore
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.tse.utils.FilteredTarExport
import org.example.project.tse.utils.Tr03153LogMessageParser
import org.example.project.tse.utils.UstarArchive
import org.junit.jupiter.api.AfterEach

/**
 * §7.1 — the software-side filtered export for TSE 2.0. Where the hardware filtered-export calls
 * are gone, pos-tse does a full export and filters the TAR itself, reading logTime /
 * transactionNumber out of each log message's DER. Exercised end to end against the virtual device,
 * which now emits real DER inside a per-message TAR.
 */
class FilteredTarExportTest {

    private val adminPuk = "888888".toByteArray()
    private val adminPin = "54321".toByteArray()
    private val timeAdminPin = "98765".toByteArray()
    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    private fun setUp(store: WormStore) {
        store.tse_setup_ext(seed, adminPuk, adminPin, timeAdminPin, clientId)
        store.tse_updateTime(System.currentTimeMillis() / 1000)
    }

    private fun fullExport(store: WormStore): ByteArray {
        val out = ByteArrayOutputStream()
        store.export_tar(object : WormExportTarCallback() {
            override fun onNewData(data: ByteArray): Int {
                out.write(data)
                return 0
            }
        })
        return out.toByteArray()
    }

    /** transactionNumber of every archive member that is a transaction log message, sorted. */
    private fun transactionNumbers(tar: ByteArray): List<Long> =
        UstarArchive.read(tar)
            .mapNotNull { Tr03153LogMessageParser.parse(it.content)?.transactionNumber }
            .sorted()

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `transaction filter keeps only the in-range transactions and preserves metadata`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            repeat(5) { store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1") } // tx 1..5

            val filtered = FilteredTarExport.byTransactionNumber(fullExport(store), start = 2, end = 4, clientId = clientId)

            assertEquals(listOf(2L, 3L, 4L), transactionNumbers(filtered), "only tx 2..4 survive")
            assertTrue(
                UstarArchive.read(filtered).any { it.name == "info.csv" },
                "non-transaction members (metadata) must be preserved",
            )
        }
    }

    @Test
    fun `time filter keeps only the transactions whose logTime is in range`() {
        // v1 lets us advance the clock freely between transactions; the software filter itself is
        // generation-agnostic, so v1-produced entries exercise it just as well.
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            val base = 1_700_000_000L
            store.tse_updateTime(base)
            val t1 = store.transaction_start(clientId, "a".toByteArray(), "Kassenbeleg-V1")
            store.tse_updateTime(base + 3600)
            val t2 = store.transaction_start(clientId, "b".toByteArray(), "Kassenbeleg-V1")
            store.tse_updateTime(base + 7200)
            val t3 = store.transaction_start(clientId, "c".toByteArray(), "Kassenbeleg-V1")

            // Range bounds come from the entries' own captured logTimes, so the assertion is robust
            // to any sub-second jitter between updateTime and the transaction.
            val filtered = FilteredTarExport.byTime(fullExport(store), t2.logTime(), t3.logTime(), clientId)

            assertEquals(
                listOf(t2.transactionNumber(), t3.transactionNumber()).sorted(),
                transactionNumbers(filtered),
                "only transactions timestamped in [t2, t3] survive; the earlier t1 is excluded",
            )
        }
    }

    @Test
    fun `an out-of-range filter keeps no transactions but still preserves metadata`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            setUp(store)
            repeat(3) { store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1") }

            val filtered = FilteredTarExport.byTransactionNumber(fullExport(store), start = 100, end = 200, clientId = clientId)

            assertTrue(transactionNumbers(filtered).isEmpty(), "no transactions fall in 100..200")
            assertTrue(UstarArchive.read(filtered).any { it.name == "info.csv" }, "metadata still preserved")
        }
    }
}
