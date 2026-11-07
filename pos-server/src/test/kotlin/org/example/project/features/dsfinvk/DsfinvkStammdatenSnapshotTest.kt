package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.EntryLogTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.features.setup.data.service.ShopInfoServiceImpl
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.FiscalShopIdentityGuard
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Round-5 KABS-2 — DSFinV-K Tz. 3.2 (p.23): the Stammdaten belonging to a Kassenabschluss are
 * „für jeden Kassenabschluss nur einmal gespeichert". A closing must therefore export the shop
 * master data that applied **when it was taken**, not whatever `shop_info` says at export time.
 *
 * Pre-fix, [DsfinvkExportServiceImpl] re-read the LIVE `ShopInfo` row for every Z_NR, so editing
 * the shop address or tax number changed the bytes of an already-delivered closing and attributed
 * historical turnover to an identity that did not apply then.
 *
 * The snapshot columns are deliberately **nullable** with a live-row fallback: 30+ test files
 * construct `ZBon.new { }` directly and bypass the repository, and NULL is also the honest
 * representation for a pre-V95 closing — backfilling unconditionally would fabricate history.
 */
@DisplayName("DSFinV-K per-closing Stammdaten snapshot (KABS-2)")
class DsfinvkStammdatenSnapshotTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // ---------------------------------------------------------------- producers

    /**
     * Seeds a closing through the REAL producer ([ZBonRepositoryImpl.createReport]) so the
     * snapshot, if any, is written by production code rather than by the fixture.
     */
    private fun seedClosingViaProducer(
        name: String = "Alt GmbH",
        strasse: String = "Hauptstr. 5",
        stnr: String = "143/815/09876",
    ): Long =
        transaction {
            val admin =
                Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                    ?: TestData.createAdmin()
            val shop =
                TestData.createShopInfo(
                    shopName = name,
                    shopAddress = strasse,
                    shopTaxNumber = stnr,
                )
            // createReport derives its window start from the first TableSession when no prior
            // closing exists — without one it throws "No session found".
            TestData.createTableWithSession("KABS2")
            ZBonRepositoryImpl().createReport(employee = admin, shopInfo = shop).id.value
        }

    /** A closing built the way 30+ existing test files build one: no snapshot columns at all. */
    private fun seedClosingWithoutSnapshot(nummer: Long = 1L): Long =
        transaction {
            val admin =
                Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                    ?: TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val now = currentTimeStamp()
            ZBon
                .new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = nummer
                }.id.value
        }

    private fun editShop(
        name: String? = null,
        strasse: String? = null,
        ustId: String? = null,
    ) = transaction {
        val shop = ShopInfo.all().first()
        name?.let { shop.shopName = it }
        strasse?.let { shop.shopAddress = it }
        ustId?.let { shop.ustIdNr = it }
    }

    // ---------------------------------------------------------------- assertions

    @Test
    fun `re-exporting a closing after a shop edit yields the ORIGINAL master data (KABS-2)`() {
        seedClosingViaProducer(name = "Alt GmbH", strasse = "Hauptstr. 5", stnr = "143/815/09876")

        val before = closingRow()
        editShop(name = "Neu GmbH", strasse = "Neumarkt 12", ustId = "DE123456789")
        val after = closingRow()

        assertEquals(before, after, "a delivered closing must re-export byte-identically after a shop edit")
        assertEquals("Alt GmbH", after["NAME"])
        assertEquals("Hauptstr. 5", after["STRASSE"])
        assertEquals("143/815/09876", after["STNR"])
    }

    @Test
    fun `location csv is snapshotted too — rule R2, both Stamm producers or neither`() {
        seedClosingViaProducer(name = "Alt GmbH", strasse = "Hauptstr. 5")

        val before = locationRow()
        editShop(name = "Neu GmbH", strasse = "Neumarkt 12")
        val after = locationRow()

        assertEquals(before, after, "location.csv must be as stable as cashpointclosing.csv")
        assertEquals("Alt GmbH", after["LOC_NAME"])
        assertEquals("Hauptstr. 5", after["LOC_STRASSE"])
    }

    @Test
    fun `a closing with no snapshot falls back to the live row`() {
        // Load-bearing for the 30+ test files that construct ZBon.new{} directly. If someone
        // later tightens the snapshot columns to NOT NULL, this is what fails first.
        transaction { TestData.createShopInfo(shopName = "Live Shop GmbH") }
        seedClosingWithoutSnapshot()

        assertNull(
            transaction { ZBon.all().first().stammName },
            "fixture must genuinely have no snapshot, else this test proves nothing",
        )
        assertEquals("Live Shop GmbH", closingRow()["NAME"])
    }

    // ------------------------------------------- round-7 Task 10a: V95/V97 mirror removed
    //
    // The Kotlin mirror of V95/V97 that lived here (`backfillStammdaten`, `repairStammdatenPlz`,
    // `pgLpad5`, `applyStammdatenMigrations`) plus its source-text drift guard were DELETED in
    // round-7 Task 10a. They pinned the migration SEMANTICS in Kotlin while the shipped PostgreSQL
    // never ran in CI (`TestDatabase` runs `initDB(runMigrations = false)`), i.e. a second, weaker
    // copy that could drift from the SQL — the C8 finding itself. `MigrationChainTest` (@Tag
    // "postgres") now executes the REAL V94→V97 against embedded PostgreSQL and asserts:
    //   • V95 backfills the Stammdaten snapshot (`stamm_name` stamped);
    //   • V97 preserves a >5-digit PLZ (123456, not the truncated 12345);
    //   • V96 adds the Stamm_Kassen columns + zbon_stamm_terminals table.
    // The freeze-on-edit path (round-6 C5) — the actual protection for un-snapshotted history — is
    // covered by the real-code tests below, which drive `ShopInfoServiceImpl.updateShopInfo`
    // directly and need no mirror.

    // ------------------------------------------- round-6 C5: the wizard audit-trail gap

    @Test
    fun `the setup wizard writes NO SHOP_INFO audit row, so V95's backfill can never run (C5)`() {
        // V95's precondition 2 ("the audit trail is demonstrably active") is satisfied only by a
        // SHOP_INFO row in entry_log. The ONLY code that writes one is ShopInfoServiceImpl
        // (CREATE / UPDATE / DELETE). The setup wizard's step 2 does not go through that service —
        // InitialSetupServiceImpl.executeShopSetup calls `shopInfoRepository.create(...)` directly.
        //
        // So on the standard install path entry_log holds no SHOP_INFO row at all, precondition 2
        // fails, and the backfill silently declines for ALL history. That is why the fix cannot be
        // "rely on the backfill" — it is why freeze-on-edit exists.
        runBlocking {
            setupWizardService().executeSetupStep(
                InitialSetupDto.InitialSetupRequestDto(
                    setupStep = SetupSteps.SHOP_SETUP,
                    data =
                        InitialSetupDto.ShopSetupRequestDto(
                            shopName = "Alt GmbH",
                            shopAddress = "Hauptstr. 5",
                            shopCity = "Dresden",
                            zipCode = 1067,
                            shopPhone = "0351 1234",
                            shopTaxNumber = "143/815/09876",
                        ),
                ),
            )
        }

        assertEquals(
            0,
            transaction {
                EntryLogTable
                    .selectAll()
                    .where { EntryLogTable.type eq ChangeEntityType.SHOP_INFO }
                    .count()
            },
            "the wizard install path writes no SHOP_INFO audit row — V95 precondition 2 is " +
                "unsatisfiable there, so the backfill declines for all history (verified end-to-end " +
                "against real PostgreSQL by MigrationChainTest). This is why freeze-on-edit exists.",
        )
    }

    // ------------------------------------------- round-6 C5: freeze-on-edit

    @Test
    fun `a fiscal shop edit freezes the PRE-EDIT Stammdaten onto closings that have none (C5)`() {
        // The closing below was taken under "Alt GmbH / Hauptstr. 5" and is exportable under that
        // identity. Editing the shop must not retroactively re-attribute its turnover: DSFinV-K
        // Tz. 3.2 (p.23) binds the Stammdaten to the Kassenabschluss. Since the backfill never ran
        // (see the test above), the edit itself is the last moment at which the OLD values are
        // still knowable — so the edit must freeze them.
        transaction { TestData.createShopInfo(shopName = "Alt GmbH", shopAddress = "Hauptstr. 5") }
        seedClosingWithoutSnapshot()
        val before = closingRow()

        updateShop(ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH", shopAddress = "Neumarkt 12"))

        assertEquals(
            "Alt GmbH",
            transaction { ZBon.all().first().stammName },
            "the edit must freeze the PRE-edit name onto the un-snapshotted closing",
        )
        assertEquals("Hauptstr. 5", transaction { ZBon.all().first().stammStrasse })
        assertEquals(before, closingRow(), "a closing must re-export byte-identically across a shop edit")
    }

    @Test
    fun `the freeze NEVER overwrites a snapshot that is already present`() {
        // Overwriting an existing snapshot would rewrite fiscal data that may already have been
        // delivered to a Prüfer — strictly worse than the NULL/live-row fallback.
        transaction { TestData.createShopInfo(shopName = "Alt GmbH", shopAddress = "Hauptstr. 5") }
        seedClosingWithoutSnapshot()

        updateShop(ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"))
        assertEquals("Alt GmbH", transaction { ZBon.all().first().stammName })

        // A SECOND fiscal edit finds the snapshot already populated and must leave it alone.
        updateShop(ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neuer GmbH"))
        assertEquals(
            "Alt GmbH",
            transaction { ZBon.all().first().stammName },
            "a later edit must not overwrite an existing snapshot",
        )
    }

    @Test
    fun `a NON-fiscal shop edit freezes nothing`() {
        // Reuses FiscalShopIdentityGuard.changesFiscalIdentity — the same predicate that decides the
        // Tz. 3.2 closing duty. A logo carries no fiscal identity, so there is nothing to preserve
        // and the closing keeps its honest NULL + live-row fallback.
        transaction { TestData.createShopInfo(shopName = "Alt GmbH") }
        seedClosingWithoutSnapshot()

        updateShop(ShopInfoDto.UpdateShopInfoRequestDto(logoUrl = "/logo/new.png"))

        assertNull(
            transaction { ZBon.all().first().stammName },
            "a non-fiscal edit must not stamp a snapshot",
        )
    }

    private fun setupWizardService() =
        InitialSetupServiceImpl(
            EmployeeRepositoryImpl(),
            EmployeeInfoRepositoryImpl(),
            ShopInfoRepositoryImpl(),
            ConfigurationRepository(),
            PaymentTypeRepositoryImpl(),
            VatRepositoryImpl(),
            ShopConfigurationManager,
        )

    /** Drives the REAL update path, including its entry-log and notification side effects. */
    private fun updateShop(request: ShopInfoDto.UpdateShopInfoRequestDto) {
        val shopId =
            transaction {
                ShopInfo
                    .all()
                    .first()
                    .id.value
            }
        runBlocking {
            ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard())
                .updateShopInfo(employeeId = adminId(), shopId = shopId, request = request)
        }
    }

    // ------------------------------------------------- Tz. 3.2 closing duty

    @Test
    fun `a fiscally relevant shop edit is rejected while business is open`() {
        val (shopId, service) = seedShopWithOpenBill()

        val ex =
            assertFailsWith<PosException> {
                runBlocking {
                    service.updateShopInfo(
                        employeeId = adminId(),
                        shopId = shopId,
                        request = ShopInfoDto.UpdateShopInfoRequestDto(shopName = "Neu GmbH"),
                    )
                }
            }
        assertEquals(PosErrorCode.SHOP_INFO_CLOSING_REQUIRED, ex.code)
        assertEquals("Alt GmbH", transaction { ShopInfo.findById(shopId)!!.shopName })
        Unit
    }

    @Test
    fun `a non-fiscal shop edit is allowed while business is open`() {
        // A logo or timezone carries no fiscal identity — demanding a Kassenabschluss for it
        // would be an invented obligation that operators would rightly route around.
        val (shopId, service) = seedShopWithOpenBill()

        runBlocking {
            service.updateShopInfo(
                employeeId = adminId(),
                shopId = shopId,
                request = ShopInfoDto.UpdateShopInfoRequestDto(logoUrl = "/logo/new.png"),
            )
        }
        assertEquals("/logo/new.png", transaction { ShopInfo.findById(shopId)!!.logoUrl })
    }

    @Test
    fun `resubmitting the CURRENT fiscal values is not a change and is allowed`() {
        val (shopId, service) = seedShopWithOpenBill()

        runBlocking {
            service.updateShopInfo(
                employeeId = adminId(),
                shopId = shopId,
                request =
                    ShopInfoDto.UpdateShopInfoRequestDto(
                        shopName = "Alt GmbH",
                        logoUrl = "/logo/new.png",
                    ),
            )
        }
        assertEquals("Alt GmbH", transaction { ShopInfo.findById(shopId)!!.shopName })
    }

    private fun adminId(): String =
        transaction {
            (Employee.find { EmployeeTable.username eq "admin" }.firstOrNull() ?: TestData.createAdmin())
                .id.value
                .toString()
        }

    /** A shop plus one bill that has not been assigned to any closing — i.e. open business. */
    private fun seedShopWithOpenBill(): Pair<Long, ShopInfoServiceImpl> {
        val shopId =
            transaction {
                val admin =
                    Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                        ?: TestData.createAdmin()
                val shop = TestData.createShopInfo(shopName = "Alt GmbH")
                val tws = TestData.createTableWithSession("OPEN")
                val session = TableSession.findById(tws.session.id)!!
                val now = currentTimeStamp()
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "OPEN-1"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                }
                shop.id.value
            }
        return shopId to ShopInfoServiceImpl(ShopInfoRepositoryImpl(), FiscalShopIdentityGuard())
    }

    // ---------------------------------------------------------------- helpers

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
            )
        }

    private fun closingRow(): Map<String, String> = readRows(export(), "cashpointclosing.csv").single()

    private fun locationRow(): Map<String, String> = readRows(export(), "location.csv").single()

    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found")
    }
}
