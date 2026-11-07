package org.example.project.features.employee

import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Employee roster order")
class EmployeeListOrderTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    @Test
    fun `getAllEmployees returns employees by employee number whatever order they were created in`() {
        TestData.createCashier(employeeNumber = "E030", username = "c30")
        TestData.createCashier(employeeNumber = "E010", username = "c10")
        TestData.createManager(employeeNumber = "E020", username = "m20")

        val numbers = transaction { EmployeeRepositoryImpl().getAllEmployees().map { it.employeeNumber } }

        assertEquals(listOf("E010", "E020", "E030"), numbers)
    }

    @Test
    fun `paging follows the same order`() {
        TestData.createCashier(employeeNumber = "E030", username = "c30")
        TestData.createCashier(employeeNumber = "E010", username = "c10")
        TestData.createManager(employeeNumber = "E020", username = "m20")

        val page = transaction { EmployeeRepositoryImpl().getAllEmployees(limit = 2, offset = 1).map { it.employeeNumber } }

        assertEquals(listOf("E020", "E030"), page)
    }
}
