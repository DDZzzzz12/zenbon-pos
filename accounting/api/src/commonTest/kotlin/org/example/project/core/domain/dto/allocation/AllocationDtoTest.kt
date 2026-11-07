package org.example.project.core.domain.dto.allocation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money crosses the wire as a JSON **string**, never a number — a bare `120.50` read back through a
 * `Double` is the one-cent defect the `:platform-money` module exists to remove. These assertions look
 * trivial; they are the only mechanical guard that someone does not "simplify" the type later.
 */
class AllocationDtoTest {
    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `every amount is emitted as a quoted string`() {
        val row =
            AllocationDto.QueueRowDto(
                transactionId = 7,
                bookingDate = "2026-03-01T00:00",
                valueDate = "2026-03-01T00:00",
                amount = "120.5000",
                openAmount = "20.5000",
                creditDebit = "DEBIT",
            )

        val encoded = json.encodeToString(AllocationDto.QueueRowDto.serializer(), row)

        assertTrue(encoded.contains("\"amount\":\"120.5000\""), encoded)
        assertTrue(encoded.contains("\"openAmount\":\"20.5000\""), encoded)
    }

    @Test
    fun `a create request round-trips with only the ids its kinds use`() {
        val request =
            AllocationDto.CreateRequestDto(
                sourceKind = "BANK_TRANSACTION",
                targetKind = "INVOICE",
                amount = "50.0000",
                bankTransactionId = 7,
                invoiceId = 3,
            )

        val decoded =
            json.decodeFromString(
                AllocationDto.CreateRequestDto.serializer(),
                json.encodeToString(AllocationDto.CreateRequestDto.serializer(), request),
            )

        assertEquals(request, decoded)
        assertEquals(null, decoded.kassenbuchHeaderId)
        assertEquals(null, decoded.counterBankAccountId)
    }

    @Test
    fun `the queue filter has exactly the two values this slice can answer`() {
        assertEquals(
            listOf("NICHT_ZUGEORDNET", "ALLE"),
            AllocationDto.QueueFilter.entries.map { it.name },
        )
    }
}
