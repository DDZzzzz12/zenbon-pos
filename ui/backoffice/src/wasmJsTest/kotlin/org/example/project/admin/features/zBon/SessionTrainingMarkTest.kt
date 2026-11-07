package org.example.project.admin.features.zBon

import kotlinx.datetime.LocalDateTime
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.zBon.presentation.sessionStatusKeys
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QA admin21 FAIL-A21: the Z-Bon "Sitzungen" tab showed a training session exactly like a real one,
 * while its receipts are badged "Training" in the Rechnungen tab. The status cell now leads with the
 * same badge.
 */
class SessionTrainingMarkTest {
    private fun session(
        isTraining: Boolean,
        completed: Boolean = true,
    ) = TableSessionDto.TableSessionResponseDto(
        id = 1,
        tableNumber = "A3",
        employeeId = "emp-2",
        employeeNumber = "K002",
        isDelivery = false,
        customer = null,
        deliveryAddress = null,
        startTime = LocalDateTime(2026, 10, 3, 17, 10),
        endTime = LocalDateTime(2026, 10, 3, 17, 20),
        completed = completed,
        isTraining = isTraining,
    )

    @Test
    fun aTrainingSessionLeadsWithTheTrainingBadge() {
        assertEquals(listOf(BillReceiptKeys.trainingBadge, AdminStringKeys.SessionStatusCompleted), sessionStatusKeys(session(isTraining = true)))
    }

    @Test
    fun aRealSessionCarriesNoBadge() {
        assertEquals(listOf(AdminStringKeys.SessionStatusCompleted), sessionStatusKeys(session(isTraining = false)))
    }

    @Test
    fun anOpenTrainingSessionIsStillMarked() {
        assertEquals(listOf(BillReceiptKeys.trainingBadge), sessionStatusKeys(session(isTraining = true, completed = false)))
    }
}
