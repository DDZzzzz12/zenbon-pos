package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.CashBookServiceImpl
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Designating the Ladenkasse's signing till: [CashBookServiceImpl.setSigningTerminal] and the candidate list. */
class CashBookSigningTerminalTest {
    private lateinit var service: CashBookServiceImpl
    private lateinit var shop: ShopInfo
    private lateinit var admin: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        startKoin { modules(serializationModule, reposModule) }
        shop = TestData.createShopInfo()
        admin = TestData.createAdmin()
        runBlocking {
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.NORMAL, timeAdminPin = "12345"))
        }
        service =
            CashBookServiceImpl(
                cashBookRepository = CashBookRepositoryImpl(),
                kassenbuchRepository = KassenbuchRepositoryImpl(testKassenbuchDocuments()),
                shopInfoRepository = ShopInfoRepositoryImpl(),
                configManager = ShopConfigurationManager,
                terminalRepository = TerminalRepositoryImpl(),
            )
    }

    @AfterEach
    fun teardown() {
        runBlocking {
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(mode = TseMode.DISABLED, timeAdminPin = "12345"))
        }
        GlobalContext.getOrNull()?.let { GlobalContext.stopKoin() }
        TestDatabase.teardown()
    }

    private fun till(
        role: TerminalRole = TerminalRole.CASHIER,
        active: Boolean = true,
    ): Terminal =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "dev-${System.nanoTime()}"
                clientId = "TER-${System.nanoTime() % 100000}"
                name = "Kasse"
                isActive = active
                fiscal = true
                tseClientRegistered = true
                this.role = role.name
                shopInfo = shop
                registeredAt = now
                lastSeenAt = now
            }
        }

    private fun fiscalBookId(): Long = transaction { CashBookRepositoryImpl().fiscalBookFor(shop).id.value }

    private fun designate(
        bookId: Long,
        terminalId: Long?,
    ) = runBlocking { service.setSigningTerminal(bookId, terminalId, admin.id.value.toString(), "dev-x") }

    @Test
    fun `designating an eligible till stores it and the response names it`() {
        val kasse = till()
        val r = designate(fiscalBookId(), kasse.id.value)
        assertEquals(kasse.id.value, r.signingTerminalId)
        assertEquals("Kasse", r.signingTerminalName)
    }

    @Test
    fun `null clears it`() {
        val fiscal = fiscalBookId()
        designate(fiscal, till().id.value)
        assertNull(designate(fiscal, null).signingTerminalId)
    }

    @Test
    fun `a non-fiscal book is refused`() {
        val tresor = runBlocking { service.create(CashBookDto.CreateRequestDto(name = "Tresor", datevAccount = "1010")) }
        val e = assertFailsWith<PosException> { designate(tresor.id, till().id.value) }
        assertEquals(PosErrorCode.CASH_BOOK_SIGNER_INELIGIBLE, e.code)
        Unit
    }

    @Test
    fun `an order station is refused with the reason`() {
        val e = assertFailsWith<PosException> { designate(fiscalBookId(), till(role = TerminalRole.ORDER_STATION).id.value) }
        assertEquals(PosErrorCode.CASH_BOOK_SIGNER_INELIGIBLE, e.code)
        assertTrue(e.message!!.contains("CASHIER"), e.message)
        Unit
    }

    @Test
    fun `candidates list only eligible tills`() {
        val ok = till()
        till(role = TerminalRole.ORDER_STATION)
        till(active = false)
        assertEquals(listOf(ok.id.value), runBlocking { service.signingTerminalCandidates(fiscalBookId()) }.map { it.terminalId })
    }

    @Test
    fun `a change writes one audit row`() {
        designate(fiscalBookId(), till().id.value)
        val rows =
            transaction {
                TransactionAuditLogTable
                    .selectAll()
                    .where { TransactionAuditLogTable.operation eq "SET_CASH_BOOK_SIGNING_TERMINAL" }
                    .count()
            }
        assertEquals(1L, rows)
    }
}
