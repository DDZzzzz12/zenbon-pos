package org.example.project.features.tse

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * B1: `process_data_hash` stores the EXACT processData handed to the TSE, but was `varchar(500)`.
 *
 * A Bestellung-V1 payload is one CSV line per order line (`<Menge>;"<Bezeichnung>";<Preis>`,
 * `\r`-separated — Anhang I p. 116) and is therefore unbounded; ~14 typical lines exceed 500
 * characters. The TSE had ALREADY SIGNED by the time the insert raised SQLSTATE 22001, so the
 * failure produced: a signature in the WORM log with no DB row and no DSFinV-K export row, an
 * order stranded PENDING (TsePendingSweeper deliberately skips ORDER), and a queue entry that
 * re-signed on the device every 60 s forever.
 *
 * A Kassennachschau comparing the TSE TAR export against the DSFinV-K data sees an unmatched
 * signed transaction plus a Vorgang the export claims is unsigned.
 */
@DisplayName("TSE processData persistence — long Bestellung payloads (B1)")
class LongProcessDataPersistenceTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @Test
    fun `a 40-line Bestellung payload persists byte-for-byte`() {
        val payload = (1..40).joinToString("\r") { "1;\"Schnitzel Wiener Art gross $it\";12.50" }
        check(payload.length > 500) {
            "fixture must exceed the old 500-char limit, otherwise this test proves nothing"
        }

        val id =
            transaction {
                TseTransaction
                    .new {
                        this.tseSerialNumber = "SN-B1"
                        this.transactionNumber = 4242L
                        this.processType = "Bestellung-V1"
                        this.processDataHash = payload
                        this.startTime = currentTimeStamp()
                        this.clientId = "TERM-B1"
                        this.tseMode = TseMode.SIMULATION
                    }.id.value
            }

        transaction {
            assertEquals(
                payload,
                TseTransaction.findById(id)!!.processDataHash,
                "the signed payload must round-trip byte-for-byte — truncating it would make the " +
                    "exported TSE_VORGANGSDATEN disagree with what the TSE actually signed",
            )
        }
    }

    @Test
    fun `a long Kassenbeleg payload with many split tenders also persists`() {
        // <Zahlungen> grows with tenders and currencies; a large split-tender Beleg is the other
        // way past 500 chars (Anhang I p. 114).
        val tenders = (1..60).joinToString("_") { "10.00:Unbar" }
        val payload = "Beleg^600.00_0.00_0.00_0.00_0.00^$tenders"
        check(payload.length > 500)

        val id =
            transaction {
                TseTransaction
                    .new {
                        this.tseSerialNumber = "SN-B1b"
                        this.transactionNumber = 4243L
                        this.processType = "Kassenbeleg-V1"
                        this.processDataHash = payload
                        this.startTime = currentTimeStamp()
                        this.clientId = "TERM-B1"
                        this.tseMode = TseMode.SIMULATION
                    }.id.value
            }

        transaction {
            assertEquals(payload, TseTransaction.findById(id)!!.processDataHash)
        }
    }
}
