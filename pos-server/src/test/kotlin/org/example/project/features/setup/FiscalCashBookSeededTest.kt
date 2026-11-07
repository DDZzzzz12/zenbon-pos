package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.database.SchemaManager
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookServiceImpl
import org.example.project.features.kassenbuch.database.tables.CashBookTable
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * A fresh install must list the fiscal Ladenkasse from the moment the shop exists.
 *
 * The `cash_book` row used to be created lazily — the first time a sale, a Z-Bon or a Kassenbuch
 * read asked for the fiscal book — so the back office's **Kassen verwalten** list on a fresh install
 * showed only a book the operator had added themselves (E2E 2026-09-20 observation). A fresh install
 * baselines Flyway without running V84's INSERT, so the seed has to live in code: the setup wizard's
 * shop step, plus an idempotent heal on every boot for shops set up before this fix.
 */
@DisplayName("The fiscal Ladenkasse exists as soon as the shop does")
class FiscalCashBookSeededTest {
    private val setupService =
        InitialSetupServiceImpl(
            EmployeeRepositoryImpl(),
            EmployeeInfoRepositoryImpl(),
            ShopInfoRepositoryImpl(),
            ConfigurationRepository(),
            PaymentTypeRepositoryImpl(),
            VatRepositoryImpl(),
            ShopConfigurationManager,
        )

    private val bookService =
        CashBookServiceImpl(
            cashBookRepository = CashBookRepositoryImpl(),
            kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
            shopInfoRepository = ShopInfoRepositoryImpl(),
            configManager = ShopConfigurationManager,
            terminalRepository = TerminalRepositoryImpl(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        // ShopConfigurationManager resolves its repository through Koin (see InitialSetupCurrencyTest).
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin { modules(serializationModule, reposModule) }
        clearShopConfigurationCache()
    }

    @AfterEach
    fun teardown() {
        clearShopConfigurationCache()
        TestDatabase.teardown()
    }

    @Suppress("UNCHECKED_CAST")
    private fun clearShopConfigurationCache() {
        val field = ShopConfigurationManager::class.java.getDeclaredField("cachedConfig")
        field.isAccessible = true
        (field.get(ShopConfigurationManager) as AtomicReference<Any?>).set(null)
    }

    private fun fiscalBookCount(): Long =
        transaction {
            CashBookTable.selectAll().where { CashBookTable.isFiscal eq true }.count()
        }

    @Test
    fun `the setup wizard's shop step creates the Ladenkasse, so the first listing shows it`() {
        runBlocking {
            setupService.executeSetupStep(
                InitialSetupDto.InitialSetupRequestDto(
                    setupStep = SetupSteps.SHOP_SETUP,
                    data =
                        InitialSetupDto.ShopSetupRequestDto(
                            shopName = "Testshop",
                            shopAddress = "Teststrasse 1",
                            shopCity = "Berlin",
                            zipCode = 10115,
                            shopPhone = "+49 30 1234567",
                            currencyCode = "EUR",
                        ),
                ),
            )
        }

        // Read straight from the table first: CashBookServiceImpl.list() does not create books, but
        // asserting on the raw row keeps this independent of any read path that might.
        assertEquals(1L, fiscalBookCount(), "the shop step must leave exactly one fiscal cash book")

        val listed = runBlocking { bookService.list() }
        assertEquals(listOf("Ladenkasse" to true), listed.map { it.name to it.isFiscal })
    }

    @Test
    fun `bootstrap heals a shop that has no fiscal book, and is idempotent`() {
        // A shop created without the wizard — the state of an install set up before this fix.
        TestData.createShopInfo()
        assertEquals(0L, fiscalBookCount(), "precondition: nothing has asked for the fiscal book yet")

        SchemaManager.bootstrapInitialData()
        SchemaManager.bootstrapInitialData()

        assertEquals(1L, fiscalBookCount(), "every boot ensures the book, and never adds a second one")
    }
}
