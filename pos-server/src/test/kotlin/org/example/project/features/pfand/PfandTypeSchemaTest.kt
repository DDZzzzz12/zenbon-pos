package org.example.project.features.pfand

import org.example.project.features.pfand.domain.models.PfandType
import org.example.project.features.product.domain.models.Product
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PfandTypeSchemaTest {
    @BeforeEach fun setup() {
        TestDatabase.setup()
    }

    @AfterEach fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `pfand type persists and a product can link to it`() {
        val cat = TestData.createCategory("Drinks")
        val colaId = TestData.createProduct(cat.id.value, productNumber = "COLA").id.value
        val waterId = TestData.createProduct(cat.id.value, productNumber = "WATER").id.value

        transaction {
            val type =
                PfandType.new {
                    name = "Einweg 0,25"
                    depositValue = BigDecimal("0.2500")
                    vatClass = "A"
                }
            val cola = Product.findById(colaId)!!
            cola.pfandType = type
            assertEquals(type.id.value, cola.pfandType?.id?.value)

            val water = Product.findById(waterId)!!
            assertNull(water.pfandType)
        }
    }
}
