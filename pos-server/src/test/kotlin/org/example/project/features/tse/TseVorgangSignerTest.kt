package org.example.project.features.tse

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.data.service.TseDeviceInfoCache
import org.example.project.features.tse.data.service.TseSignRequest
import org.example.project.features.tse.data.service.TseVorgangSigner
import org.example.project.features.tse.database.tables.TseExplanationTable
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.tse.domain.models.TseTransactionState
import org.example.project.features.tse.domain.repository.TseTransactionRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The signer records the TANR before the device signs, so a failed write can no longer throw a
 * signature away (E2E R2-7; design 2026-09-22 §4.1).
 */
class TseVorgangSignerTest {
    private lateinit var device: ScriptedTseSignProvider
    private val audit by lazy { TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json) }

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        device = ScriptedTseSignProvider()
        // The device cache is process-wide; a serial left by another test would decide this one's
        // device-swap check.
        TseDeviceInfoCache.forgetSerialForTests()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun signer(repository: TseTransactionRepository = TseTransactionRepositoryImpl()) = TseVorgangSigner(device, repository, audit)

    private val receipt =
        TseSignRequest(
            referenceId = "79",
            entityType = TseEntityType.BILL,
            processType = "Kassenbeleg-V1",
            processData = "Beleg^10.00_0.00_0.00_0.00_0.00^10.00:Bar",
            clientId = "TER-008",
        )

    private fun rows(): List<TseTransaction> = transaction { TseTransaction.all().sortedBy { it.id.value }.toList() }

    private fun signOrFail(
        s: TseVorgangSigner = signer(),
        request: TseSignRequest = receipt,
    ): PosException = assertFailsWith<PosException> { runBlocking { s.sign(request) } }

    @Test
    fun `a Vorgang is started, recorded, then finished on one TANR`() {
        val tx = runBlocking { signer().sign(receipt) }

        assertEquals(listOf("start", "finish:100:Kassenbeleg-V1"), device.calls)
        val row = rows().single()
        assertEquals(tx.id.value, row.id.value)
        assertEquals(TseTransactionState.FINISHED, row.state)
        assertEquals(100L, row.transactionNumber)
        assertEquals("79", row.referenceId)
        assertEquals("BILL", row.entityType)
        assertEquals(ScriptedTseSignProvider.SERIAL_HEX, row.tseSerialNumber, "serial stored as hex (TR-03153-1 §9.3.2)")
        assertEquals(TseMode.NORMAL, row.tseMode)
        assertEquals(receipt.processData, row.processDataHash)
        assertEquals(ScriptedTseSignProvider.START_LOG_TIME, row.tseStartTimestamp)
        assertNotNull(row.signature)
    }

    @Test
    fun `an order is finished as an order`() {
        runBlocking {
            signer().sign(receipt.copy(entityType = TseEntityType.ORDER, processType = "Bestellung-V1", processData = "1;\"Pils\";4.50"))
        }
        assertEquals("finish:100:Bestellung-V1", device.calls.last())
    }

    @Test
    fun `a finished Vorgang is adopted, never signed twice`() {
        runBlocking { signer().sign(receipt) }
        device.calls.clear()

        runBlocking { signer().sign(receipt) }

        assertEquals(emptyList(), device.calls)
        assertEquals(1, rows().size)
    }

    @Test
    fun `a finish that never reached the device is retried on the same TANR`() {
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))

        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)
        assertEquals(TseTransactionState.STARTED, rows().single().state)

        runBlocking { signer().sign(receipt) }

        assertEquals(listOf("start", "finish:100:Kassenbeleg-V1", "finish:100:Kassenbeleg-V1"), device.calls)
        val row = rows().single()
        assertEquals(100L, row.transactionNumber)
        assertEquals(TseTransactionState.FINISHED, row.state)
    }

    @Test
    fun `a TANR the device already closed is superseded and replaced`() {
        device.loseNextFinishReply = true
        assertEquals(PosErrorCode.TSE_COMMUNICATION_TIMEOUT, signOrFail().code)

        runBlocking { signer().sign(receipt) }

        val (old, new) = rows()
        assertEquals(100L, old.transactionNumber)
        assertEquals(TseTransactionState.SUPERSEDED, old.state)
        assertEquals(new.id.value, old.supersededById)
        assertEquals(101L, new.transactionNumber)
        assertEquals(TseTransactionState.FINISHED, new.state)
        val supersededAudit =
            runBlocking {
                audit.getTransactionLogs(
                    TransactionAuditDto.TransactionAuditQueryDto(
                        operation = TseVorgangSigner.TSE_TRANSACTION_SUPERSEDED,
                        fromDate = "2000-01-01T00:00:00",
                        toDate = "2100-01-01T00:00:00",
                        limit = 10,
                    ),
                )
            }
        assertEquals(1, supersededAudit.size)
        assertEquals(
            1,
            transaction { TseExplanationTable.selectAll().count() }.toInt(),
            "the system explains a superseded TANR itself",
        )
    }

    @Test
    fun `a replacement that is closed too surfaces as a failure instead of looping`() {
        val notOpen = PosException(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, HttpStatusCode.Conflict, "not open")
        device.failNext("finish", notOpen)
        device.failNext("finish", notOpen)

        assertEquals(PosErrorCode.TSE_TRANSACTION_NOT_OPEN, signOrFail().code)
        assertEquals(listOf(TseTransactionState.SUPERSEDED, TseTransactionState.SUPERSEDED), rows().map { it.state })
    }

    @Test
    fun `a failed first write leaves only an open TANR on the device, and the retry signs cleanly`() {
        val failing = FailingWritesRepository(TseTransactionRepositoryImpl(), failCreates = true)

        assertEquals(PosErrorCode.TSE_SIGNING_FAILED, signOrFail(signer(failing)).code)
        assertEquals(setOf(100L), device.openOnDevice, "window c: an empty open transaction, aborted by maintenance")
        assertEquals(emptyList(), rows())

        runBlocking { signer().sign(receipt) }
        assertEquals(101L, rows().single().transactionNumber)
    }

    @Test
    fun `a failed second write is resolved by superseding, never by a silent duplicate`() {
        val failing = FailingWritesRepository(TseTransactionRepositoryImpl(), failUpdates = true)

        assertEquals(PosErrorCode.TSE_SIGNING_FAILED, signOrFail(signer(failing)).code)
        assertEquals(TseTransactionState.STARTED, rows().single().state)
        assertEquals(setOf(100L), device.finishedOnDevice)

        runBlocking { signer().sign(receipt) }

        assertEquals(listOf(TseTransactionState.SUPERSEDED, TseTransactionState.FINISHED), rows().map { it.state })
    }

    @Test
    fun `signing inside a database transaction is refused as a signing failure, not a 500`() {
        // PosException/TSE_SIGNING_FAILED, because that is the only failure every call site already
        // routes into "queue for Nachsignierung"; an IllegalStateException would escape as a 500
        // after the sale had committed.
        val failure = assertFailsWith<PosException> { transaction { runBlocking { signer().sign(receipt) } } }

        assertEquals(PosErrorCode.TSE_SIGNING_FAILED, failure.code)
        assertEquals(emptyList(), device.calls)
    }

    @Test
    fun `a stored TANR is never finished on a device that did not open it`() {
        // The first attempt records TANR 100 on device A and loses the write of its finish.
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)
        assertEquals(TseTransactionState.STARTED, rows().single().state)

        // A replacement TSE is fitted: new serial, and its counter restarts — so 100 is very
        // likely OPEN on it, belonging to somebody else's Vorgang.
        device.serial = ScriptedTseSignProvider.REPLACEMENT_SERIAL_BASE64
        device.nextTanr = 1L
        device.openOnDevice += 100L
        // The next sale teaches the process which device is attached now (it takes TANR 1).
        runBlocking { signer().sign(receipt.copy(referenceId = "80")) }
        device.calls.clear()

        runBlocking { signer().sign(receipt) }

        assertEquals(
            listOf("start", "finish:2:Kassenbeleg-V1"),
            device.calls,
            "the retry must NOT call finish(100) at all: on this device 100 is another Vorgang",
        )
        assertEquals(setOf(100L), device.openOnDevice, "the stranger's transaction is left untouched")
        val (old, _, new) = rows()
        assertEquals(TseTransactionState.SUPERSEDED, old.state)
        assertEquals(100L, old.transactionNumber)
        assertEquals(ScriptedTseSignProvider.SERIAL_HEX, old.tseSerialNumber)
        assertEquals(null, old.signature, "no signature may be written onto the old device's row")
        assertEquals(TseTransactionState.FINISHED, new.state)
        assertEquals("79", new.referenceId)
        assertEquals(2L, new.transactionNumber)
        assertEquals(ScriptedTseSignProvider.REPLACEMENT_SERIAL_HEX, new.tseSerialNumber)
        assertEquals(new.id.value, old.supersededById)
    }

    @Test
    fun `a resumed TANR is never finished while the attached device is unidentified`() {
        // The canonical case: the TSE dies mid-signing, is swapped, and the server restarts — so
        // nothing has identified the attached device. TANR 100 is very likely open on the new
        // device as SOMEBODY ELSE's Vorgang, and finishing it is irreversible, while starting a
        // fresh transaction here is always safe. Unknown must therefore mean "do not finish".
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)
        TseDeviceInfoCache.forgetSerialForTests()
        device.calls.clear()

        runBlocking { signer().sign(receipt) }

        assertEquals(
            listOf("start", "finish:101:Kassenbeleg-V1"),
            device.calls,
            "no finish(100) may be attempted while the device is unidentified",
        )
        assertEquals(setOf(100L), device.openOnDevice, "TANR 100 is left open for the maintenance task, not closed blind")
        val (old, new) = rows()
        assertEquals(TseTransactionState.SUPERSEDED, old.state)
        assertEquals(100L, old.transactionNumber)
        assertEquals(null, old.signature)
        assertEquals(TseTransactionState.FINISHED, new.state)
        assertEquals(101L, new.transactionNumber)
        assertEquals(new.id.value, old.supersededById)
    }

    @Test
    fun `a resumed TANR IS finished on the same number once the device is identified`() {
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)
        // What `LocalTseAdminProvider.getInfo` does after a restart — no signing, no extra device
        // I/O, and it is what keeps the ordinary resume off the supersede path.
        TseDeviceInfoCache.forgetSerialForTests()
        TseDeviceInfoCache.recordSerial(ScriptedTseSignProvider.SERIAL_BASE64)
        device.calls.clear()

        runBlocking { signer().sign(receipt) }

        assertEquals(listOf("finish:100:Kassenbeleg-V1"), device.calls, "the same TANR, not a new one")
        val row = rows().single()
        assertEquals(100L, row.transactionNumber)
        assertEquals(TseTransactionState.FINISHED, row.state)
    }

    @Test
    fun `a finish answered by a foreign device is never written onto the row`() {
        // The device IS identified and agrees with the row, so the finish goes out — and the reply
        // comes back under a different serial. Detection, not prevention: the device has already
        // closed something, and all we can still refuse is STORING that signature here.
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)
        device.serialOnNextFinish = ScriptedTseSignProvider.REPLACEMENT_SERIAL_BASE64

        runBlocking { signer().sign(receipt) }

        val (old, new) = rows()
        assertEquals(TseTransactionState.SUPERSEDED, old.state)
        assertEquals(null, old.signature, "the foreign signature must not land on the original row")
        assertEquals(null, old.signatureCounter)
        assertEquals(ScriptedTseSignProvider.SERIAL_HEX, old.tseSerialNumber, "nor may a foreign serial overwrite it")
        assertEquals(TseTransactionState.FINISHED, new.state)
        assertEquals(101L, new.transactionNumber, "the Vorgang is signed again under a new TANR")
        assertEquals(new.id.value, old.supersededById)
    }

    @Test
    fun `a finish answering for a different TANR is refused the same way`() {
        runBlocking { signer().sign(receipt) }
        val signedRow = rows().single()

        // A second Vorgang whose STARTED row was recorded, with the device answering for the
        // PREVIOUS transaction number instead of the one asked for.
        val other = receipt.copy(referenceId = "80")
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail(request = other).code)
        device.answerForTanr = signedRow.transactionNumber

        runBlocking { signer().sign(other) }

        val otherRows = rows().filter { it.referenceId == "80" }
        assertEquals(
            listOf(TseTransactionState.SUPERSEDED, TseTransactionState.FINISHED),
            otherRows.map { it.state },
        )
        assertEquals(null, otherRows.first().signature)
    }

    @Test
    fun `simulation mode quick-signs, because simulated start numbers collide within a second`() {
        device.mode = "SIMULATION"
        runBlocking { signer().sign(receipt) }

        assertEquals(listOf("quickSign"), device.calls)
        val row = rows().single()
        assertEquals(TseMode.SIMULATION, row.tseMode)
        assertEquals(TseTransactionState.FINISHED, row.state)
    }

    @Test
    fun `switching to SIMULATION supersedes a stranded TANR instead of adding a second live row`() {
        device.failNext("finish", PosException(PosErrorCode.TSE_CONNECTION_FAILED, HttpStatusCode.ServiceUnavailable, "down"))
        assertEquals(PosErrorCode.TSE_CONNECTION_FAILED, signOrFail().code)

        device.mode = "SIMULATION"
        runBlocking { signer().sign(receipt) }

        // Two LIVE rows for one Vorgang is what `tse_transaction_one_live_per_vorgang` refuses on
        // PostgreSQL, so leaving the STARTED row alone would fail every retry from here on.
        assertEquals(listOf(TseTransactionState.SUPERSEDED, TseTransactionState.FINISHED), rows().map { it.state })
        assertEquals(TseMode.SIMULATION, rows().last().tseMode)
    }

    @Test
    fun `a signed row from before entity_type existed is adopted by reference and exact payload`() {
        transaction {
            TseTransaction.new {
                tseSerialNumber = ScriptedTseSignProvider.SERIAL_HEX
                transactionNumber = 42L
                processType = "Kassenbeleg-V1"
                processDataHash = receipt.processData
                signature = "c2ln"
                clientId = "TER-008"
                startTime = currentTimeStamp()
                tseMode = TseMode.NORMAL
                referenceId = "79"
            }
        }

        runBlocking { signer().sign(receipt) }

        assertEquals(emptyList(), device.calls)
    }
}
