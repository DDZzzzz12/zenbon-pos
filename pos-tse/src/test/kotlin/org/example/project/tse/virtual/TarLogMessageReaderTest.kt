package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormStore
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.tse.utils.TarLogMessageReader
import org.junit.jupiter.api.AfterEach

class TarLogMessageReaderTest {

    private val seed = "SwissbitSwissbit".toByteArray()
    private val clientId = "POS-TERMINAL-1"

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `reads the transaction log messages out of a virtual-device export TAR`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, "888888".toByteArray(), "54321".toByteArray(), "98765".toByteArray(), clientId)
            store.tse_updateTime(System.currentTimeMillis() / 1000)
            repeat(3) { store.transaction_start(clientId, "data".toByteArray(), "Kassenbeleg-V1") } // tx 1..3

            val out = ByteArrayOutputStream()
            store.export_tar(object : WormExportTarCallback() {
                override fun onNewData(data: ByteArray): Int { out.write(data); return 0 }
            })

            val records = TarLogMessageReader.read(out.toByteArray())

            // info.csv is not a log message, so exactly the 3 transaction logs are returned.
            assertEquals(3, records.size)
            assertEquals(listOf(1L, 2L, 3L), records.mapNotNull { it.transactionNumber }.sorted())
            assertTrue(records.all { it.signatureCounter != null }, "every record carries a signature counter")
        }
    }

    @Test
    fun `a virtual-device export has gap-free transaction numbers and unique signature counters`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            store.tse_setup_ext(seed, "888888".toByteArray(), "54321".toByteArray(), "98765".toByteArray(), clientId)
            store.tse_updateTime(System.currentTimeMillis() / 1000)
            repeat(6) { store.transaction_start(clientId, "d".toByteArray(), "Kassenbeleg-V1") }

            val out = ByteArrayOutputStream()
            store.export_tar(object : WormExportTarCallback() {
                override fun onNewData(data: ByteArray): Int { out.write(data); return 0 }
            })

            val records = TarLogMessageReader.read(out.toByteArray())

            // The two CRITICAL invariants LogSequenceVerifier enforces (pos-server), checked here
            // with the reader alone since that verifier can't be imported from this module.
            val tx = records.mapNotNull { it.transactionNumber }.sorted()
            assertEquals((1L..6L).toList(), tx) // gap-free
            val counters = records.mapNotNull { it.signatureCounter }
            assertEquals(counters.size, counters.distinct().size) // no duplicates
        }
    }
}
