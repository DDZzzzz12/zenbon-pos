package org.example.project.utils.formatter

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.tse.data.repository.TseTransactionRepositoryImpl
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R2-5: TR-03153-1 §9.3.2 allows only the hex string for identifying and assigning a TSE, so a
 * Base64 serial is not an identity at all. Every new NORMAL row carries hex in both columns (a
 * SIMULATION row has no device and no hex serial), and a row
 * written before V82 still resolves to hex through `serialHex()`.
 */
class TseSerialHexTest {
    @BeforeEach fun setup() = TestDatabase.setup()

    @AfterEach fun teardown() = TestDatabase.teardown()

    @Test
    fun `every new row stores the hex serial in both columns`() {
        val row =
            transaction {
                runBlocking {
                    TseTransactionRepositoryImpl().create(
                        tseSerialNumber = BASE64_SERIAL,
                        transactionNumber = 1L,
                        processType = "Kassenbeleg-V1",
                        processDataHash = null,
                        signature = null,
                        startTime = null,
                        clientId = "TER-008",
                        tseMode = TseMode.NORMAL,
                    )
                }
            }
        assertEquals(HEX_SERIAL, transaction { TseTransaction.findById(row.id)!!.tseSerialNumber })
        assertEquals(HEX_SERIAL, transaction { TseTransaction.findById(row.id)!!.tseSerialHex })
    }

    @Test
    fun `a simulated serial survives the write path unchanged`() {
        // What `generateSimulatedSignResult` actually returns: Base64("SIM-TSE-001"). It is NOT a
        // 32-byte serial, so nothing may decode it — its hex form is itself decodable as Base64,
        // and a second pass invents a serial that matches no entry of SIMULATED_SERIALS, blinding
        // the month-end no_simulated_signatures check.
        val row =
            transaction {
                runBlocking {
                    TseTransactionRepositoryImpl().create(
                        tseSerialNumber = SIMULATED_BASE64_SERIAL,
                        transactionNumber = 5L,
                        processType = "Kassenbeleg-V1",
                        processDataHash = null,
                        signature = "c2ln",
                        startTime = null,
                        clientId = "TER-008",
                        tseMode = TseMode.SIMULATION,
                    )
                }
            }
        transaction {
            val stored = TseTransaction.findById(row.id)!!
            assertEquals(SIMULATED_BASE64_SERIAL, stored.tseSerialNumber)
            // No device, no hex serial (QA rt12 e): the Base64 placeholder no longer poses as one.
            assertEquals(null, stored.tseSerialHex)
            // Readers go through serialHex(), which falls back to the raw column: unchanged.
            assertEquals(SIMULATED_BASE64_SERIAL, stored.serialHex())
        }
    }

    @Test
    fun `normalising an already-normalised serial changes nothing`() {
        // `create` normalises, and its callers already do — so the second pass must be a no-op.
        val row =
            transaction {
                runBlocking {
                    TseTransactionRepositoryImpl().create(
                        tseSerialNumber = TseSerialFormat.normalizeToHex(SIMULATED_BASE64_SERIAL),
                        transactionNumber = 6L,
                        processType = "Kassenbeleg-V1",
                        processDataHash = null,
                        signature = "c2ln",
                        startTime = null,
                        clientId = "TER-008",
                        tseMode = TseMode.SIMULATION,
                    )
                }
            }
        // A SIMULATION row keeps the serial only in the raw column (its hex column is null).
        assertEquals(SIMULATED_BASE64_SERIAL, transaction { TseTransaction.findById(row.id)!!.tseSerialNumber })
    }

    @Test
    fun `a row written without the hex column still resolves to hex`() {
        val serial =
            transaction {
                TseTransaction
                    .new {
                        tseSerialNumber = BASE64_SERIAL
                        transactionNumber = 2L
                        tseMode = TseMode.NORMAL
                    }.serialHex()
            }
        assertEquals(HEX_SERIAL, serial)
    }

    @Test
    fun `the stored hex column wins over the raw one`() {
        val serial =
            transaction {
                TseTransaction
                    .new {
                        tseSerialNumber = BASE64_SERIAL
                        tseSerialHex = HEX_SERIAL
                        transactionNumber = 3L
                        tseMode = TseMode.NORMAL
                    }.serialHex()
            }
        assertEquals(HEX_SERIAL, serial)
    }

    private companion object {
        const val BASE64_SERIAL = "LS+I+8MLvJrOt1wo4hIQWZZ4bHoKCLLGz3Qwbtx17Eg="

        /** Base64("SIM-TSE-001") — what the simulated finish returns; see SIMULATED_SERIALS. */
        const val SIMULATED_BASE64_SERIAL = "U0lNLVRTRS0wMDE="
        const val HEX_SERIAL = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"
    }
}
