package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tips sheet / Mitarbeiter view: the operator is named by the HR name, the username only as fallback. */
class EmployeeGroupShownNameTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun group(
        employeeName: String,
        displayName: String = "",
    ) = StatisticsDto.EmployeeGroupDto(
        employeeNumber = "K003",
        employeeName = employeeName,
        totalRevenue = "0.00",
        totalTips = "0.00",
        billCount = 0,
        bills = emptyList(),
        displayName = displayName,
    )

    @Test
    fun showsTheDisplayNameTheServerSent() {
        assertEquals("Klara Kern", group(employeeName = "k003", displayName = "Klara Kern").shownName())
    }

    @Test
    fun aBlankDisplayNameFallsBackToTheUsername() {
        assertEquals("k003", group(employeeName = "k003", displayName = " ").shownName())
    }

    @Test
    fun aResponseFromAnOlderServerWithoutDisplayNameStillDecodesAndShowsTheUsername() {
        val body =
            """{"employeeNumber":"K003","employeeName":"k003","totalRevenue":"1.00","totalTips":"0.50","billCount":1,"bills":[]}"""

        val decoded = json.decodeFromString(StatisticsDto.EmployeeGroupDto.serializer(), body)

        assertEquals("", decoded.displayName)
        assertEquals("k003", decoded.shownName())
    }

    @Test
    fun theUsernameFieldStaysOnTheWire() {
        val encoded = json.encodeToString(StatisticsDto.EmployeeGroupDto.serializer(), group("k003", "Klara Kern"))

        val back = json.decodeFromString(StatisticsDto.EmployeeGroupDto.serializer(), encoded)
        assertEquals("k003", back.employeeName)
        assertEquals("Klara Kern", back.displayName)
    }
}
