package org.example.project.features.pfand

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.features.pfand.data.repository.PfandTypeRepositoryImpl
import org.example.project.features.pfand.data.service.PfandTypeServiceImpl
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PfandTypeServiceTest {
    private lateinit var service: PfandTypeServiceImpl

    @BeforeEach fun setup() {
        TestDatabase.setup()
        TestData.createShopInfo()
        TestData.createDefaultVatRates() // seeds classes A + B only (no C)
        service = PfandTypeServiceImpl(PfandTypeRepositoryImpl())
    }

    @AfterEach fun teardown() {
        TestDatabase.teardown()
    }

    private fun backing(
        deposit: Boolean,
        typeId: Long,
    ): Product? =
        transaction {
            Product
                .find {
                    (ProductTable.pfandTypeId eq typeId) and
                        (if (deposit) ProductTable.isDeposit eq true else ProductTable.isDepositReturn eq true)
                }.firstOrNull()
        }

    @Test fun `create seeds two priced backing products in PFAND category`() =
        runBlocking {
            val dto = service.create(PfandTypeDto.CreatePfandTypeRequestDto("Einweg 0,25", "0.25", "A"))
            transaction {
                val dep = backing(true, dto.id)!!
                val ret = backing(false, dto.id)!!
                assertEquals("PFAND-${dto.id}", dep.productNumber)
                assertEquals("LEERGUT-${dto.id}", ret.productNumber)
                assertEquals(0, BigDecimal("0.25").compareTo(dep.price))
                assertEquals(0, BigDecimal("0.25").compareTo(ret.price))
                assertEquals("PFAND", dep.category.name)
                assertNotNull(dep.pfandType)
                assertNotNull(ret.pfandType)
                assertTrue(dep.isDeposit)
                assertTrue(ret.isDepositReturn)
            }
        }

    @Test fun `update reprices both backing products`() =
        runBlocking {
            val dto = service.create(PfandTypeDto.CreatePfandTypeRequestDto("T", "0.25", "A"))
            service.update(dto.id, PfandTypeDto.UpdatePfandTypeRequestDto("T", "0.15", "A", true))
            transaction {
                assertEquals(0, BigDecimal("0.15").compareTo(backing(true, dto.id)!!.price))
                assertEquals(0, BigDecimal("0.15").compareTo(backing(false, dto.id)!!.price))
            }
        }

    @Test fun `deactivate deactivates both backing products`() =
        runBlocking {
            val dto = service.create(PfandTypeDto.CreatePfandTypeRequestDto("T", "0.25", "A"))
            service.deactivate(dto.id)
            transaction {
                assertFalse(backing(true, dto.id)!!.isActive)
                assertFalse(backing(false, dto.id)!!.isActive)
            }
        }

    @Test fun `vat class with no Vat row is rejected`() =
        runBlocking {
            // class "C" is NOT seeded by createDefaultVatRates (only A + B)
            assertFailsWith<PosException> {
                service.create(PfandTypeDto.CreatePfandTypeRequestDto("T", "0.25", "C"))
            }
            Unit
        }
}
