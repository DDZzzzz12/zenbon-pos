package org.example.project.repository

import org.example.project.features.table.data.repository.RTableRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RTableRepositoryForUpdateTest {
    private val repository = RTableRepositoryImpl()

    @Test
    fun `getTableByIdForUpdate returns the locked table row`() {
        testApp(
            setupData = {
                val table = TestData.createTable(tableNumber = "LOCK_T1")
                transaction {
                    val locked = repository.getTableByIdForUpdate(table.id.value)
                    assertEquals(table.id.value, locked?.id?.value)
                    assertEquals("LOCK_T1", locked?.tableNumber)
                }
            },
        ) { _ -> }
    }

    @Test
    fun `getTableByIdForUpdate returns null for a missing table`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                transaction {
                    assertNull(repository.getTableByIdForUpdate(999_999L))
                }
            },
        ) { _ -> }
    }
}
