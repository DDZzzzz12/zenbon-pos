package org.example.project.features.auth

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.auth.data.service.ApprovalRequestServiceImpl
import org.example.project.features.auth.domain.service.ApprovalDetailsResolver
import org.example.project.features.order.data.service.OrderApprovalDetailsResolver
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("Approval request details are rendered through the resolver list")
class ApprovalDetailsResolverTest {
    private val raised = mutableListOf<String>()

    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() {
        raised.forEach { TemporarySessionStore.removeRequest(it) }
        TestDatabase.teardown()
    }

    private fun pendingDetails(
        service: ApprovalRequestServiceImpl,
        details: Map<String, String>,
    ): Map<String, String> =
        runBlocking {
            raised +=
                service.createApprovalRequest(
                    employeeId = "not-a-uuid",
                    employeeNumber = "X",
                    deviceId = "dev-adr",
                    terminalName = "T",
                    operation = "TRANSFER_ITEMS",
                    requiredCapabilities = setOf(Capability.ORDER_WRITE),
                    details = details,
                )
            service.getPendingRequests().single { it.requestId == raised.last() }.details
        }

    @Test
    fun `test_approval_details_resolved_via_resolver_list`() {
        val table = TestData.createTable(tableNumber = "ADR_T")
        val session = TestData.createTableWithSession(tableNumber = "ADR_S").session
        val item = TestData.createOrderItemInSession(session, ItemStatus.OPEN)
        val itemId = item.id.value
        val expectedItem = transaction { OrderItem.findById(item.id)!!.product.let { "${it.productNumber}-${it.name}" } }
        val service = ApprovalRequestServiceImpl(listOf(OrderApprovalDetailsResolver()))

        val details = pendingDetails(service, mapOf("itemIds" to "$itemId", "targetTableId" to "${table.id.value}", "reason" to "r"))

        assertEquals(expectedItem, details["items"])
        assertEquals("ADR_T", details["targetTable"])
        assertEquals("r", details["reason"])
        assertFalse("itemIds" in details || "targetTableId" in details)
    }

    @Test
    fun `resolvers are chained in order and an unresolvable id degrades without failing`() {
        val service =
            ApprovalRequestServiceImpl(
                listOf(
                    ApprovalDetailsResolver { it + ("a" to "1") },
                    ApprovalDetailsResolver { it + ("b" to (it["a"] + "2")) },
                    OrderApprovalDetailsResolver(),
                ),
            )

        val details = pendingDetails(service, mapOf("itemIds" to "999999", "targetTableId" to "999999"))

        assertEquals("12", details["b"])
        assertEquals("", details["items"])
        assertFalse("targetTable" in details)
    }
}
