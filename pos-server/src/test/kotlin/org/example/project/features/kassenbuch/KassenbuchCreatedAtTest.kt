package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round-4 KABS-1 regression guard.
 *
 * The broken form passed `currentTimeStamp()` to Exposed's `Column.default(value)`, which takes a
 * VALUE evaluated exactly once, at table-object initialisation. Every row inserted without an
 * explicit value therefore shared one fabricated wall-clock instant, and that literal was baked
 * into the DDL default. For Kassenbuch this broke DSFinV-K closing membership (the export filters
 * on `created_at`) and fabricated every KB BON_START/BON_ENDE.
 *
 * The fix is `clientDefault { currentTimeStamp() }`, which evaluates the lambda per insert.
 *
 * ## What this test does and does not guard (round-5 correction)
 *
 * This test asserts the OBSERVABLE contract: two entries created via
 * [KassenbuchRepositoryImpl.createEntry] get distinct, non-frozen `createdAt` values. That is worth
 * pinning, but it is **not** a guard on the column default, and the KDoc previously implied it was.
 *
 * The same commit also added an explicit `this.createdAt = currentTimeStamp()` inside
 * `createEntry`. Exposed's `EntityClass` default-copy is filtered by `col !in writeValues`, so on
 * this code path the column default is never consulted at all — the test would stay green if
 * `KassenbuchHeaderTable.createdAt` were reverted to `.default(currentTimeStamp())`. Its
 * red-before-the-fix evidence was genuine but non-discriminating: both changes landed together, and
 * the explicit stamp alone accounts for it.
 *
 * The actual guard for the `.default` vs `.clientDefault` distinction — across every table, not
 * just this one — is
 * `org.example.project.core.database.FrozenTimestampDefaultGuardTest`, which matches the forbidden
 * shape in the sources directly. Keep both: this one covers the repository contract, that one
 * covers the bug class.
 */
@DisplayName("KassenbuchHeader.createdAt is stamped per insert (KABS-1)")
class KassenbuchCreatedAtTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private var belegCounter = 0

    @Suppress("DefaultLocale")
    private fun seedEntry(gross: String = "100.0"): Long =
        transaction {
            val belegNr = "2026-07-${String.format("%04d", ++belegCounter)}"
            repository
                .createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = belegNr,
                            vorzBetrag = gross,
                            belegDatum = "18.07.2026",
                            belegtext = "KABS-1 timestamp probe",
                        ),
                    lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = gross)),
                ).id.value
        }

    @Test
    fun `two entries created apart get different createdAt`() {
        val first = seedEntry()
        Thread.sleep(1100) // second-resolution column: outlast the coarsest plausible precision
        val second = seedEntry()

        val (a, b) =
            transaction {
                KassenbuchHeader.findById(first)!!.createdAt to KassenbuchHeader.findById(second)!!.createdAt
            }

        assertNotEquals(
            a,
            b,
            "createdAt must be the row's own creation time, not a frozen table-init default (KABS-1)",
        )
        assertTrue(a < b, "the earlier entry must carry the earlier createdAt, got a=$a b=$b")
    }

    @Test
    fun `createdAt is close to the real clock, not a table-init instant`() {
        val before = currentTimeStamp()
        val id = seedEntry()
        val after = currentTimeStamp()

        val createdAt = transaction { KassenbuchHeader.findById(id)!!.createdAt }

        assertTrue(
            createdAt >= before && createdAt <= after,
            "createdAt ($createdAt) must fall inside the insert window [$before, $after] — " +
                "a frozen default predates the test entirely",
        )
    }
}
