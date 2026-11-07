package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.tse.TseEntityType
import org.example.project.core.domain.enums.tse.TseLateSigningReason
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.tse.data.repository.TseLateSigningRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A single-bill read carries the Nachsignierung note (tse_late_signing); a bill signed on time none. */
class BillSigningNoteTest {
    private fun newBill(
        number: String,
        admin: Employee,
    ): Long =
        transaction {
            val tws = TestData.createTableWithSession("BSN$number")
            val now = currentTimeStamp()
            Bill
                .new {
                    this.employeeId = admin
                    this.tableSession = TableSession.findById(tws.session.id)!!
                    this.billNumber = number
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.tseSigningStatus = TseSigningStatus.QUEUED
                }.id.value
        }

    @Test
    fun `a queued bill's detail carries its reason, and a bill never queued carries none`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                val admin = TestData.createAdmin()
                val queuedId = newBill("7001", admin)
                val onTimeId = newBill("7002", admin)
                transaction {
                    TseLateSigningRepositoryImpl().recordQueued(
                        TseEntityType.BILL,
                        queuedId.toString(),
                        currentTimeStamp(),
                        TseLateSigningReason.TSE_UNAVAILABLE,
                        "TSE_HARDWARE_NOT_FOUND: TSE hardware not found",
                    )
                }
                val service = GlobalContext.get().get<BillService>()

                val queued = runBlocking { service.getBillById(queuedId.toString()) }
                val note = assertNotNull(queued.tseSigningNote)
                assertTrue(note.pending)
                assertEquals("TSE_UNAVAILABLE", note.reasonCode)
                assertEquals("TSE_HARDWARE_NOT_FOUND: TSE hardware not found", note.reasonText)

                assertNull(runBlocking { service.getBillByNumber("7002") }.tseSigningNote)
                assertNull(runBlocking { service.getBillById(onTimeId.toString()) }.tseSigningNote)
            },
        ) { _ -> }
}
