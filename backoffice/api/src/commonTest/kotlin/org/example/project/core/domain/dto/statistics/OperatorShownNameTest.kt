package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cancelled items carry `displayName` the same way the by-employee groups do (UI review
 * follow-up 6; the employee-metrics DTO it also covered was dead and removed in follow-up 7): shown when sent, the username otherwise, and an older server's response
 * without it still decodes.
 */
class OperatorShownNameTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cancelled(displayName: String) =
        StatisticsDto.CanceledItemDto(
            itemName = "Schnitzel",
            unitPrice = "4.50",
            quantity = 1,
            totalAmount = "4.50",
            billNumber = "R-1",
            employeeName = "k003",
            time = "2026-10-16T12:00",
            reason = "",
            displayName = displayName,
        )

    @Test
    fun theDisplayNameIsShownAndABlankOneFallsBackToTheUsername() {
        assertEquals("Klara Kern", cancelled("Klara Kern").shownName())
        assertEquals("k003", cancelled(" ").shownName())
    }

    @Test
    fun aCancelledItemFromAnOlderServerWithoutDisplayNameStillDecodes() {
        val body =
            """{"itemName":"Schnitzel","unitPrice":"4.50","quantity":1,"totalAmount":"4.50","billNumber":"R-1","employeeName":"k003","time":"t","reason":""}"""

        val decoded = json.decodeFromString(StatisticsDto.CanceledItemDto.serializer(), body)

        assertEquals("", decoded.displayName)
        assertEquals("k003", decoded.shownName())
    }

    @Test
    fun theUsernameFieldStaysOnTheWire() {
        val back =
            json.decodeFromString(
                StatisticsDto.CanceledItemDto.serializer(),
                json.encodeToString(StatisticsDto.CanceledItemDto.serializer(), cancelled("Klara Kern")),
            )
        assertEquals("k003", back.employeeName)
        assertEquals("Klara Kern", back.displayName)
    }
}
