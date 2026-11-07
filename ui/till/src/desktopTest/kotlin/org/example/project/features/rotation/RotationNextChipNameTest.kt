package org.example.project.features.rotation

import org.example.project.core.domain.dto.rotation.RotationDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.ui.screens.dashboard.rotationNextChipName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The chip on both dashboard top bars: shown exactly when the store names someone. */
class RotationNextChipNameTest {
    @Test
    fun hiddenWhenNobodyIsNext() {
        assertNull(rotationNextChipName(null))
    }

    @Test
    fun showsTheDisplayName() {
        val ref = RotationDto.EmployeeRefDto(employeeId = "id", employeeNumber = "E1", displayName = "Anna")
        assertEquals("Anna", rotationNextChipName(ref))
    }

    @Test
    fun fallsBackToTheEmployeeNumber() {
        val ref = RotationDto.EmployeeRefDto(employeeId = "id", employeeNumber = "E1", displayName = null)
        assertEquals("E1", rotationNextChipName(ref))
    }

    @Test
    fun visibilityFollowsTheStoreAlone() {
        // A server that switched round-robin off sends next = null; one that has it on names someone.
        val store = RotationNextStore()
        store.apply(update(1, RotationDto.EmployeeRefDto(employeeId = "id", employeeNumber = "E1", displayName = "Anna")))
        assertEquals("Anna", rotationNextChipName(store.next.value))
        store.apply(update(2, null))
        assertNull(rotationNextChipName(store.next.value))
    }

    private fun update(
        version: Long,
        next: RotationDto.EmployeeRefDto?,
    ) = NotificationDto.RotationNextUpdateDto(timestamp = "", version = version, next = next)
}
