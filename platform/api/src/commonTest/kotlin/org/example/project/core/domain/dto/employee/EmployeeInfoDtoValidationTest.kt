package org.example.project.core.domain.dto.employee

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FO-77: a blank value in an HR update is the "clear" marker, so `validate()` must let a blank email
 * through while still refusing a malformed one and a blank name. FO-31: the summary's display name.
 */
class EmployeeInfoDtoValidationTest {
    @Test
    fun blankEmail_isAccepted_asTheClearMarker() {
        EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = "").validate()
        EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = "   ").validate()
    }

    @Test
    fun malformedEmail_isRejected() {
        assertFailsWith<IllegalArgumentException> { EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = "nope").validate() }
    }

    @Test
    fun wellFormedEmail_isAccepted() {
        EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = "a@b.de").validate()
    }

    @Test
    fun blankNames_areRejected_theyCannotBeCleared() {
        assertFailsWith<IllegalArgumentException> { EmployeeInfoDto.UpdateEmployeeInfoRequestDto(firstName = " ").validate() }
        assertFailsWith<IllegalArgumentException> { EmployeeInfoDto.UpdateEmployeeInfoRequestDto(lastName = "").validate() }
    }

    private fun summary(displayName: String? = null) =
        EmployeeDto.EmployeeSummaryResponseDto(
            id = "1",
            username = "k001",
            employeeNumber = "K001",
            role = "CASHIER",
            employmentStatus = "ACTIVE",
            isActive = true,
            hasEmployeeInfo = displayName != null,
            displayName = displayName,
        )

    @Test
    fun shownName_prefersTheHrName_thenFallsBackToUsername() {
        assertEquals("Klara Kern", summary("Klara Kern").shownName())
        assertEquals("k001", summary(null).shownName())
        assertEquals("k001", summary("  ").shownName())
    }

    @Test
    fun matches_findsTheHrNameUsernameAndNumber() {
        val row = summary("Klara Kern")
        assertTrue(row.matches("kern"))
        assertTrue(row.matches("k001"))
        assertTrue(row.matches(""))
        assertFalse(row.matches("zzz"))
    }

    @Test
    fun anOlderServerResponseWithoutDisplayName_stillDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val dto =
            json.decodeFromString<EmployeeDto.EmployeeSummaryResponseDto>(
                """{"id":"1","username":"u","employeeNumber":"K1","role":"CASHIER","employmentStatus":"ACTIVE","isActive":true,"hasEmployeeInfo":false}""",
            )
        assertNull(dto.displayName)
    }
}
