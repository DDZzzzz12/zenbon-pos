package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.utils.DsfinvkFieldLimits
import org.example.project.export.dsfinvk.model.DsfinvkIndexXml
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Round-7 C7 — no producer enforced the Anhang E `Feldlänge` / index.xml `MaxLength` on
 * operator-supplied strings, so an over-long value reached the CSV and produced a **formally
 * invalid package**: auditor software parses each CSV through the ordered `VariableColumn`
 * descriptors in index.xml, and a value longer than its declared `MaxLength` breaks that contract.
 *
 * **Rejected at the write boundary, not truncated at export.** Truncating would emit a value the
 * operator never entered into a fiscal record that must not be silently rewritten; a wrong-but-
 * intact value stays auditable, a shortened one does not. Same ruling as C8 earlier in this round.
 *
 * Scope is the **shop Stammdaten** — `NAME`, `STRASSE`, `ORT`, `STNR`. They are emitted for every
 * Z_NR into BOTH `cashpointclosing.csv` and `location.csv` (via `stammdatenFor`, no truncation
 * anywhere on that path), they are unambiguously operator-typed in one place, and their DB columns
 * are far wider than the declared field: `shop_name` 255 vs 60, `shop_address` 500 vs 60,
 * `shop_city` 100 vs 62, `shop_tax_number` 50 vs 20. A long German legal company name clears 60
 * without trying.
 *
 * Deliberately NOT in scope here, and reported rather than half-fixed: BEDIENER_NAME, KUNDE_NAME,
 * ZAHLART_NAME, TERMINAL_*, and the three `.take(255)` BON_NOTIZ truncations.
 *
 * **Update (isolated audit 2026-07-22, M1):** BEDIENER_NAME, KUNDE_NAME, WARENGR and ZAHLART_NAME
 * are now closed the same way — reject at the write boundary — in
 * [DsfinvkOperatorFieldLengthTest]. ARTIKELTEXT is the one field that cannot be, because it is a
 * generated composite of every bill-wide promotion name and overflows only in combination; it is
 * clamped with a visible ellipsis instead, which is admissible because it is a label rather than
 * an operator-entered value (STK_BR carries the auditable amount). TERMINAL_* remains open: the
 * snapshot columns already match the declared widths, so the overflow would occur at
 * snapshot-insert time rather than in the CSV.
 */
@DisplayName("DSFinV-K Anhang E field lengths — shop Stammdaten (C7)")
class DsfinvkFieldLengthTest {
    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // ------------------------------------------------------- the limits are the SPEC's

    /**
     * R7 — derive one side of the assertion from the spec, not from the producer.
     *
     * [DsfinvkIndexXml] is the transcription of the reference `02_index.xml/index.xml`; every one
     * of its 100 `Alpha` lengths was verified equal to the reference's `MaxLength`. Pinning the
     * shared constants to it means a hand-edited constant cannot silently drift away from the
     * declared schema the package is validated against.
     */
    @Test
    fun `the enforced limits equal the lengths declared in index-xml`() {
        val closing = DsfinvkIndexXml.tables().first { it.url == "cashpointclosing.csv" }

        fun declared(column: String) = (closing.columns.first { it.name == column }.type as DsfinvkIndexXml.Alpha).maxLength

        assertEquals(declared("NAME"), DsfinvkFieldLimits.NAME)
        assertEquals(declared("STRASSE"), DsfinvkFieldLimits.STRASSE)
        assertEquals(declared("ORT"), DsfinvkFieldLimits.ORT)
        assertEquals(declared("STNR"), DsfinvkFieldLimits.STNR)
    }

    /** location.csv re-emits the same four values, so one limit must serve both files. */
    @Test
    fun `location-csv declares the same lengths as cashpointclosing-csv`() {
        val loc = DsfinvkIndexXml.tables().first { it.url == "location.csv" }

        fun declared(column: String) = (loc.columns.first { it.name == column }.type as DsfinvkIndexXml.Alpha).maxLength

        assertEquals(DsfinvkFieldLimits.NAME, declared("LOC_NAME"))
        assertEquals(DsfinvkFieldLimits.STRASSE, declared("LOC_STRASSE"))
        assertEquals(DsfinvkFieldLimits.ORT, declared("LOC_ORT"))
    }

    // ----------------------------------------------------------- the write boundary

    @Test
    fun `an over-long shop name is rejected on update`() {
        val shopId = seedShop()

        assertFailsWith<PosException> {
            runBlocking { updateShop(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopName = "N".repeat(61))) }
        }
        assertEquals(
            "Alt GmbH",
            transaction { ShopInfo.findById(shopId)!!.shopName },
            "the rejected value must not have been stored",
        )
    }

    @Test
    fun `an over-long street, city and tax number are rejected on update`() {
        val shopId = seedShop()

        assertFailsWith<PosException> {
            runBlocking { updateShop(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopAddress = "S".repeat(61))) }
        }
        assertFailsWith<PosException> {
            runBlocking { updateShop(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopCity = "O".repeat(63))) }
        }
        assertFailsWith<PosException> {
            runBlocking { updateShop(shopId, ShopInfoDto.UpdateShopInfoRequestDto(shopTaxNumber = "1".repeat(21))) }
        }
        Unit
    }

    @Test
    fun `a value exactly AT the declared length is accepted`() {
        // The limit is inclusive — rejecting 60 would be an invented restriction, and an
        // off-by-one here is the difference between a guard and an obstruction.
        val shopId = seedShop()

        runBlocking {
            updateShop(
                shopId,
                ShopInfoDto.UpdateShopInfoRequestDto(
                    shopName = "N".repeat(DsfinvkFieldLimits.NAME),
                    shopAddress = "S".repeat(DsfinvkFieldLimits.STRASSE),
                    shopCity = "O".repeat(DsfinvkFieldLimits.ORT),
                    shopTaxNumber = "1".repeat(DsfinvkFieldLimits.STNR),
                ),
            )
        }
        assertEquals(
            DsfinvkFieldLimits.NAME,
            transaction { ShopInfo.findById(shopId)!!.shopName.length },
        )
    }

    @Test
    fun `the create DTO rejects an over-long name and accepts one at the limit`() {
        // The setup wizard writes through the CREATE DTO, which is the other way these values
        // first enter the system — guarding only the update path would leave the install path open.
        assertFailsWith<IllegalArgumentException> {
            createDto(shopName = "N".repeat(DsfinvkFieldLimits.NAME + 1)).validate()
        }
        createDto(shopName = "N".repeat(DsfinvkFieldLimits.NAME)).validate()
        Unit
    }

    // ---------------------------------------------------------------- helpers

    private fun createDto(shopName: String) =
        ShopInfoDto.CreateShopInfoRequestDto(
            shopName = shopName,
            shopAddress = "Hauptstr. 5",
            shopCity = "Berlin",
            zipCode = 12345,
            shopCountry = "DE",
            shopPhone = "+49 30 123456",
            shopTimezone = "Europe/Berlin",
        )

    private fun seedShop(): Long =
        transaction {
            Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin()
            TestData.createShopInfo(shopName = "Alt GmbH").id.value
        }

    private suspend fun updateShop(
        shopId: Long,
        request: ShopInfoDto.UpdateShopInfoRequestDto,
    ) {
        val adminId =
            transaction {
                Employee
                    .find { EmployeeTable.username eq "admin" }
                    .first()
                    .id.value
                    .toString()
            }
        ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard())
            .updateShopInfo(employeeId = adminId, shopId = shopId, request = request)
    }
}
