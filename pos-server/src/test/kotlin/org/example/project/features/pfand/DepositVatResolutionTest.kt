package org.example.project.features.pfand

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.features.pfand.data.repository.PfandTypeRepositoryImpl
import org.example.project.features.pfand.data.service.PfandTypeServiceImpl
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.config.VatMappingUtil
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class DepositVatResolutionTest {
    @BeforeEach fun setup() {
        TestDatabase.setup()
        TestData.createShopInfo()
        TestData.createDefaultVatRates() // A = 19%, B = 7%
    }

    @AfterEach fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `deposit product resolves to its PfandType vat class regardless of drink rate`() =
        runBlocking {
            val dto =
                PfandTypeServiceImpl(PfandTypeRepositoryImpl())
                    .create(PfandTypeDto.CreatePfandTypeRequestDto("Einweg", "0.25", "A")) // class A = 19%
            transaction {
                val deposit = Product.find { ProductTable.productNumber eq "PFAND-${dto.id}" }.first()
                val vat = VatMappingUtil.determineDepositVat(deposit)
                // 19%, not the 7% that a reduced-rate drink would carry.
                assertEquals(0, BigDecimal("0.1900").compareTo(vat.vatRate))
            }
        }
}
