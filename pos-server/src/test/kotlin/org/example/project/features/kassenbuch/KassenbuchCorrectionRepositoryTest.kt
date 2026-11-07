package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("KassenbuchRepositoryImpl – correction link queries")
class KassenbuchCorrectionRepositoryTest {
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

    // -------------------------------------------------------------------------
    // Helper: seed a simple Kassenbuch header via the repository (within a
    // transaction — matches the pattern from KassenbuchServiceReplaceDocumentTest)
    // -------------------------------------------------------------------------

    private var belegCounter = 0

    @Suppress("DefaultLocale")
    private fun seedEntry(
        entryType: KassenbuchEntryType = KassenbuchEntryType.CASH_SALE,
        gross: String = "100.0",
        correctsHeaderId: Long? = null,
        correctionRole: CorrectionRole? = null,
    ): KassenbuchHeader =
        transaction {
            val belegNr = "2026-06-${String.format("%04d", ++belegCounter)}"
            repository.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = entryType,
                        belegNr = belegNr,
                        vorzBetrag = gross,
                        belegDatum = "24.06.2026",
                        belegtext = "Test entry",
                        correctsHeaderId = correctsHeaderId,
                        correctionRole = correctionRole,
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = gross),
                    ),
            )
        }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `hasReversal is false until a REVERSAL entry references the header`() {
        val original = seedEntry(KassenbuchEntryType.CASH_SALE, gross = "100.0")

        transaction {
            assertFalse(repository.hasReversal(original.id.value))
        }

        seedEntry(
            entryType = KassenbuchEntryType.CORRECTION_OUT,
            gross = "100.0",
            correctsHeaderId = original.id.value,
            correctionRole = CorrectionRole.REVERSAL,
        )

        transaction {
            assertTrue(repository.hasReversal(original.id.value))
        }
    }

    @Test
    fun `hasReversal remains false when only a RE_ENTRY references the header`() {
        val original = seedEntry(KassenbuchEntryType.CASH_SALE, gross = "50.0")
        seedEntry(
            entryType = KassenbuchEntryType.CASH_SALE,
            gross = "50.0",
            correctsHeaderId = original.id.value,
            correctionRole = CorrectionRole.RE_ENTRY,
        )

        transaction {
            assertFalse(repository.hasReversal(original.id.value))
        }
    }

    @Test
    fun `findCorrectionsFor returns both reversal and re-entry`() {
        val original = seedEntry(KassenbuchEntryType.CASH_SALE, gross = "100.0")
        seedEntry(KassenbuchEntryType.CORRECTION_OUT, "100.0", original.id.value, CorrectionRole.REVERSAL)
        seedEntry(KassenbuchEntryType.CASH_SALE, "90.0", original.id.value, CorrectionRole.RE_ENTRY)

        transaction {
            val corrections = repository.findCorrectionsFor(original.id.value)
            assertEquals(2, corrections.size)
            assertEquals(
                setOf(CorrectionRole.REVERSAL, CorrectionRole.RE_ENTRY),
                corrections.mapNotNull { it.correctionRole }.toSet(),
            )
        }
    }

    @Test
    fun `findCorrectionsFor returns empty list when no corrections exist`() {
        val original = seedEntry(KassenbuchEntryType.CASH_SALE, gross = "75.0")

        transaction {
            val corrections = repository.findCorrectionsFor(original.id.value)
            assertEquals(0, corrections.size)
        }
    }
}
