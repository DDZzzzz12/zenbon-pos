package org.example.project.features.bill

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestDatabase
import org.example.project.utils.builder.tse.resolvedStartLogTimeSeconds
import org.example.project.utils.builder.tse.toQrCodeDataDto
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M6 (isolated audit 2026-07-22): the receipt QR and `transactions_tse.csv` resolved
 * `<start-zeit>` / `TSE_TA_START` through DIFFERENT fallback chains, so one Vorgang could report
 * two different start times.
 *
 *   QR      `overrideStartTimeSeconds ?: tseStartTimestamp ?: tseTimestamp ?: startTime`
 *   export  `tseStartTimestamp ?: startTime`
 *
 * Anhang I p. 123 defines `<start-zeit>` as "Log-Time der StartTransaction-Operation der TSE" and
 * Anhang E p. 104 defines `TSE_TA_START` as exactly the same value. They cannot legitimately differ
 * — an auditor comparing a scanned receipt against the export for the same Vorgang would see a
 * discrepancy that looks like tampering.
 *
 * They diverge for precisely one state: `tseStartTimestamp == null && tseTimestamp != null` —
 * legacy rows predating `V91__tse_start_timestamp`, and remote peers whose `SignResultDto` omits
 * `startTimestamp` (acknowledged in `RealTseTransactionServiceImpl`).
 *
 * **Unified on the QR's chain, not the export's.** Anhang E requires a TSE *log time*.
 * `tseTimestamp` is one — the finish log time — whereas `startTime` is the SERVER clock and no TSE
 * log time at all. And since the two-step start/finish flow was removed with quick sale, every
 * transaction is now a single atomic `quickSign`, where start and finish are one WormAPI sequence
 * milliseconds apart. So the finish log time is a tight approximation of the start log time, while
 * the server clock can be skewed from the device arbitrarily. Dropping the rung instead would have
 * made both sides *consistently worse*.
 */
@DisplayName("QR <start-zeit> and TSE_TA_START agree (M6)")
class QrStartTimeParityTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun tx(
        startTs: Long?,
        finishTs: Long?,
    ): TseTransaction =
        transaction {
            TseTransaction.new {
                this.tseSerialNumber = "SN-QRP"
                this.transactionNumber = 501L
                this.processType = "Kassenbeleg-V1"
                this.processDataHash = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar"
                this.signature = "c2ln"
                this.signatureCounter = 9L
                this.signatureAlgorithm = "ecdsa-plain-SHA384"
                this.publicKey = "cHVi"
                this.clientId = "TERM-QRP"
                this.startTime = currentTimeStamp()
                this.endTime = currentTimeStamp()
                this.tseStartTimestamp = startTs
                this.tseTimestamp = finishTs
                this.tseMode = TseMode.NORMAL
            }
        }

    /**
     * The divergent state. Before the fix the QR reported the FINISH log time here while the export
     * reported the server clock — two different instants for one Vorgang.
     */
    @Test
    fun `with no persisted start log time both fall back to the finish log time`() {
        val finish = 1_574_422_189L
        val t = tx(startTs = null, finishTs = finish)

        transaction {
            assertEquals(
                finish,
                t.resolvedStartLogTimeSeconds(),
                "the shared resolver must prefer the TSE's own finish log time over the server " +
                    "clock: Anhang E requires a TSE log time, and after the quick-sale removal " +
                    "start and finish are one atomic quickSign sequence",
            )
        }
    }

    @Test
    fun `the persisted start log time always wins`() {
        val start = 1_574_422_188L
        val t = tx(startTs = start, finishTs = 1_574_422_189L)

        transaction {
            assertEquals(
                start,
                t.resolvedStartLogTimeSeconds(),
                "a genuine StartTransaction log time must never be displaced by the finish time",
            )
        }
    }

    /**
     * Non-vacuity: the two chains must be the SAME function, not two copies that happen to agree
     * today. This is what actually prevents the drift the finding describes.
     */
    @Test
    fun `both surfaces read the same resolver`() {
        val t = tx(startTs = null, finishTs = 1_574_422_189L)
        transaction {
            val viaQr = t.toQrCodeDataDto()!!.startTimeSeconds
            assertEquals(
                t.resolvedStartLogTimeSeconds(),
                viaQr,
                "the QR must not carry its own private fallback chain",
            )
        }
    }
}
