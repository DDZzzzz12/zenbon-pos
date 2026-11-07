package org.example.project.features.zBon

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.data.repository.TerminalRepositoryImpl
import org.example.project.features.terminal.domain.TSE_DISABLED_CLIENT_ID
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.ClosingSigner
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ClosingSignerTest {
    private lateinit var shop: ShopInfo
    private val repo = TerminalRepositoryImpl()
    private val adminWeb = "admin-web-0b5e"

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        shop = TestData.createShopInfo()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun till(
        device: String,
        client: String,
        active: Boolean = true,
        registered: Boolean = true,
    ): Terminal =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = device
                clientId = client
                name = client
                isActive = active
                fiscal = true
                tseClientRegistered = registered
                role = TerminalRole.CASHIER.name
                shopInfo = shop
                registeredAt = now
                lastSeenAt = now
            }
        }

    private fun fiscalBook(): CashBook = CashBookRepositoryImpl().fiscalBookFor(shop)

    private fun designate(t: Terminal?) = transaction { fiscalBook().signingTerminal = t }

    private fun resolve(
        device: String,
        mode: TseMode = TseMode.NORMAL,
    ) = transaction { ClosingSigner.resolve(repo, device, mode, fiscalBook()) }

    @Test
    fun `a designated fit till signs for any caller`() {
        designate(till("dev-1", "TER-001"))
        val r = resolve(adminWeb)
        assertIs<ClosingSigner.Resolution.Resolved>(r)
        assertEquals("TER-001", r.clientId)
    }

    @Test
    fun `a designated till outranks the calling till`() {
        designate(till("dev-1", "TER-001"))
        till("dev-2", "TER-002")
        assertEquals("TER-001", (resolve("dev-2") as ClosingSigner.Resolution.Resolved).clientId)
    }

    @Test
    fun `a deactivated designated till is a problem, not a fallback`() {
        designate(till("dev-1", "TER-001", active = false))
        till("dev-2", "TER-002")
        val r = resolve("dev-2")
        assertIs<ClosingSigner.Resolution.Unavailable>(r)
        assertEquals(ClosingSignerProblem.INACTIVE, r.problem)
        assertEquals("TER-001", r.signerName)
    }

    @Test
    fun `an unregistered designated till is a problem in NORMAL only`() {
        designate(till("dev-1", "TER-001", registered = false))
        assertEquals(ClosingSignerProblem.NOT_REGISTERED, (resolve(adminWeb) as ClosingSigner.Resolution.Unavailable).problem)
        assertIs<ClosingSigner.Resolution.Resolved>(resolve(adminWeb, TseMode.DISABLED))
    }

    @Test
    fun `without a designation the calling till signs`() {
        till("dev-2", "TER-002")
        assertEquals("TER-002", (resolve("dev-2") as ClosingSigner.Resolution.Resolved).clientId)
    }

    @Test
    fun `without a designation the adminApp has no signer`() {
        till("dev-2", "TER-002")
        val r = resolve(adminWeb)
        assertIs<ClosingSigner.Resolution.Unavailable>(r)
        assertEquals(ClosingSignerProblem.NOT_DESIGNATED, r.problem)
    }

    @Test
    fun `without a designation an unknown device has no signer`() {
        assertEquals(ClosingSignerProblem.NOT_DESIGNATED, (resolve("unknown") as ClosingSigner.Resolution.Unavailable).problem)
    }

    @Test
    fun `require turns NOT_DESIGNATED into ZBON_SIGNER_NOT_DESIGNATED`() {
        val e = assertFailsWith<PosException> { transaction { ClosingSigner.require(repo, adminWeb, TseMode.NORMAL, fiscalBook()) } }
        assertEquals(PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED, e.code)
        Unit
    }

    @Test
    fun `require turns an unfit designated till into ZBON_SIGNER_UNAVAILABLE naming it`() {
        designate(till("dev-1", "TER-001", active = false))
        val e = assertFailsWith<PosException> { transaction { ClosingSigner.require(repo, adminWeb, TseMode.NORMAL, fiscalBook()) } }
        assertEquals(PosErrorCode.ZBON_SIGNER_UNAVAILABLE, e.code)
        assertEquals("The Ladenkasse's signing till 'TER-001' cannot sign: it is deactivated.", e.message)
        Unit
    }

    @Test
    fun `require words each unfit-till reason in plain language, never as an enum name`() {
        val reasons =
            mapOf(
                "NOT_FISCAL" to "it is not a fiscal till",
                "NOT_REGISTERED" to "it is not registered with the TSE",
            )
        reasons.forEach { (defect, words) ->
            val sentence = ClosingSigner.unfitReason(ClosingSignerProblem.valueOf(defect))
            assertEquals(words, sentence)
        }
        assertEquals("it is deactivated", ClosingSigner.unfitReason(ClosingSignerProblem.INACTIVE))
    }

    @Test
    fun `require keeps the historic refusal for an unfit calling till`() {
        till("dev-2", "TER-002", registered = false)
        val e = assertFailsWith<PosException> { transaction { ClosingSigner.require(repo, "dev-2", TseMode.NORMAL, fiscalBook()) } }
        assertEquals(PosErrorCode.TERMINAL_NOT_TSE_REGISTERED, e.code)
        Unit
    }

    @Test
    fun `with the TSE disabled the adminApp resolves to the placeholder, never a problem`() {
        val r = resolve(adminWeb, TseMode.DISABLED)
        assertIs<ClosingSigner.Resolution.Resolved>(r)
        assertEquals(TSE_DISABLED_CLIENT_ID, r.clientId)
        assertEquals(null, r.signerName)
    }

    @Test
    fun `with the TSE disabled an unknown device resolves to the placeholder`() {
        assertEquals(TSE_DISABLED_CLIENT_ID, (resolve("unknown", TseMode.DISABLED) as ClosingSigner.Resolution.Resolved).clientId)
    }

    @Test
    fun `with the TSE disabled a designated till still attributes the entries`() {
        designate(till("dev-1", "TER-001"))
        val r = resolve(adminWeb, TseMode.DISABLED)
        assertEquals("TER-001", (r as ClosingSigner.Resolution.Resolved).clientId)
        assertEquals("TER-001", r.signerName)
    }

    @Test
    fun `with the TSE disabled a non-fiscal designated till is not a problem`() {
        designate(till("dev-1", "TER-001").also { transaction { it.fiscal = false } })
        assertIs<ClosingSigner.Resolution.Resolved>(resolve(adminWeb, TseMode.DISABLED))
    }

    @Test
    fun `with the TSE disabled a DEACTIVATED designated till is still refused - isActive is checked in every mode`() {
        designate(till("dev-1", "TER-001", active = false))
        val r = resolve(adminWeb, TseMode.DISABLED)
        assertIs<ClosingSigner.Resolution.Unavailable>(r)
        assertEquals(ClosingSignerProblem.INACTIVE, r.problem)
    }

    @Test
    fun `with the TSE disabled require returns the placeholder instead of a 409`() {
        val signer = transaction { ClosingSigner.require(repo, adminWeb, TseMode.DISABLED, fiscalBook()) }
        assertEquals(ClosingSigner.Signer(TSE_DISABLED_CLIENT_ID, ClosingSigner.Source.TSE_DISABLED_PLACEHOLDER), signer)
    }

    @Test
    fun `require reports a designated till as DESIGNATED_TILL, whoever called`() {
        designate(till("dev-1", "TER-001"))
        till("dev-2", "TER-002")
        val signer = transaction { ClosingSigner.require(repo, "dev-2", TseMode.NORMAL, fiscalBook()) }
        assertEquals(ClosingSigner.Signer("TER-001", ClosingSigner.Source.DESIGNATED_TILL), signer)
    }

    @Test
    fun `require reports the calling till as CALLING_TILL when nothing is designated`() {
        till("dev-2", "TER-002")
        val signer = transaction { ClosingSigner.require(repo, "dev-2", TseMode.NORMAL, fiscalBook()) }
        assertEquals(ClosingSigner.Signer("TER-002", ClosingSigner.Source.CALLING_TILL), signer)
    }
}
