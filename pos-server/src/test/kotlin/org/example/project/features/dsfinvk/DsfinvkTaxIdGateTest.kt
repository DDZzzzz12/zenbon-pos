package org.example.project.features.dsfinvk

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-3 KABS-6 (Anhang E p. 68, § 14 Abs. 4 Nr. 2 UStG): a Kassenabschluss MUST carry either a
 * Steuernummer (STNR) or a USt-IdNr (USTID). A shop with BOTH empty produces a formally invalid
 * export — the exporter must hard-gate it (generateExport throws 422) and the pre-export
 * validation must surface it (validateExportData reports an error), while EITHER field present
 * lets the export proceed.
 */
@DisplayName("DSFinV-K generateExport / validateExportData — Steuernummer or USt-IdNr gate (KABS-6)")
class DsfinvkTaxIdGateTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Seeds a minimal exportable closing (one ZBon + one paid Bill) against the given shop. */
    private fun seedClosing(shop: ShopInfo) {
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("TAXID1")
            val now = currentTimeStamp()

            val zBon =
                ZBon.new {
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
                    this.zbonNummer = 1L
                }
            val session = TableSession.findById(tws.session.id)!!
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "TAXID-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }
        }
    }

    private val request = DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01")

    @Test
    fun `both Steuernummer and USt-IdNr empty - generateExport throws 422`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosing(shop)

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus, "must reject with 422 UnprocessableEntity")
        assertTrue(
            ex.message?.contains("Steuernummer") == true && ex.message?.contains("USt-IdNr") == true,
            "message must mention the tax-id requirement; got: ${ex.message}",
        )
    }

    @Test
    fun `only Steuernummer set - generateExport succeeds`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)

        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty when a Steuernummer is configured")
    }

    @Test
    fun `only USt-IdNr set - generateExport succeeds`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = "DE123456789") }
        seedClosing(shop)

        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty when a USt-IdNr is configured")
    }

    @Test
    fun `both empty - validateExportData reports the tax-id error`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosing(shop)

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(!result.isValid, "validation must not be valid when both tax ids are empty")
        assertTrue(
            result.errors.any { it.contains("Steuernummer") && it.contains("USt-IdNr") },
            "errors must include the tax-id requirement; got: ${result.errors}",
        )
    }

    @Test
    fun `Steuernummer set - validateExportData has no tax-id error`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(
            result.errors.none { it.contains("Steuernummer") },
            "no tax-id error expected when a Steuernummer is configured; got: ${result.errors}",
        )
    }

    // ================================================================================
    // Round-7 C4 — the gate must validate the EXPORTED CLOSING, not the live shop row
    // ================================================================================
    //
    // Round-5's KABS-2 made cashpointclosing.csv / location.csv emit a per-closing Stammdaten
    // SNAPSHOT (`ZBon.stammStnr` / `ZBon.stammUstId`, resolved by `stammdatenFor`) while this gate
    // kept reading the LIVE `shop_info` row. Anhang E p. 68 („Es muss entweder die Steuernummer
    // oder die USt-IdNr … angegeben werden", § 14 Abs. 4 Nr. 2 UStG) constrains the row that is
    // actually SHIPPED, so the gate has to be evaluated per exported closing, through the same
    // resolution the emitter uses.

    /**
     * Seeds a closing through the REAL producer ([ZBonRepositoryImpl.createReport]), so the
     * Stammdaten snapshot — including `stammStnr` / `stammUstId` — is written by production code.
     */
    private fun seedClosingViaProducer(shop: ShopInfo) {
        transaction {
            val admin =
                Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                    ?: TestData.createAdmin()
            // createReport derives its window start from the first TableSession when no prior
            // closing exists — without one it throws "No session found".
            TestData.createTableWithSession("C4")
            ZBonRepositoryImpl().createReport(employee = admin, shopInfo = shop)
        }
    }

    /** Edits the LIVE shop row only — the already-taken closing keeps its snapshot. */
    private fun setLiveTaxIds(
        stNr: String?,
        ustId: String?,
    ) = transaction {
        val shop = ShopInfo.all().first()
        shop.shopTaxNumber = stNr
        shop.ustIdNr = ustId
    }

    @Test
    fun `snapshot has NEITHER tax id while the live row has one - generateExport is rejected`() {
        // The real harm. The operator took a closing before configuring any tax id, then
        // configured a Steuernummer afterwards. The live row now looks fine, but
        // cashpointclosing.csv still emits the snapshot: STNR and USTID both empty — a formally
        // invalid row under Anhang E p. 68. The gate must reject THIS package.
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosingViaProducer(shop)
        assertNull(
            transaction { ZBon.all().first().stammStnr },
            "fixture must genuinely have an empty snapshot STNR, else this test proves nothing",
        )
        setLiveTaxIds(stNr = "12345/67890", ustId = null)

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus, "must reject with 422 UnprocessableEntity")
        assertTrue(
            ex.message?.contains("Steuernummer") == true && ex.message?.contains("USt-IdNr") == true,
            "message must mention the tax-id requirement; got: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("Z_NR") == true,
            "an operator must be told WHICH closing is unfixable; got: ${ex.message}",
        )
    }

    @Test
    fun `snapshot has NEITHER tax id while the live row has one - validateExportData reports it`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosingViaProducer(shop)
        setLiveTaxIds(stNr = "12345/67890", ustId = null)

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(!result.isValid, "the pre-flight check must agree with the export it precedes")
        assertTrue(
            result.errors.any { it.contains("Steuernummer") && it.contains("USt-IdNr") },
            "errors must include the tax-id requirement; got: ${result.errors}",
        )
    }

    @Test
    fun `snapshot HAS a tax id while the live row has none - generateExport succeeds`() {
        // The opposite direction, guarding against over-tightening: the closing was taken while a
        // USt-IdNr was configured and its snapshot carries it, so the shipped row is valid no
        // matter what the live row says today.
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = "DE123456789") }
        seedClosingViaProducer(shop)
        setLiveTaxIds(stNr = null, ustId = null)

        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "a closing whose SNAPSHOT carries a USt-IdNr must still export")
    }

    @Test
    fun `snapshot HAS a tax id while the live row has none - validateExportData reports no error`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = "DE123456789") }
        seedClosingViaProducer(shop)
        setLiveTaxIds(stNr = null, ustId = null)

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(
            result.errors.none { it.contains("Steuernummer") },
            "no tax-id error expected when the closing's snapshot carries a USt-IdNr; got: ${result.errors}",
        )
    }

    @Test
    fun `a PRE-SNAPSHOT closing still falls back to the live row and exports unchanged`() {
        // `stammName == null` is the "no snapshot" discriminator and its live-row fallback is
        // load-bearing (pre-V95 closings + 30+ fixtures). Routing the gate through `stammdatenFor`
        // must not disturb it: live row has a Steuernummer, snapshot is absent, export proceeds
        // exactly as it did before this change.
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop) // ZBon.new {} directly — no snapshot columns at all

        assertNull(
            transaction { ZBon.all().first().stammName },
            "fixture must genuinely have no snapshot, else this test proves nothing",
        )
        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "a pre-snapshot closing must keep exporting off the live row")

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(
            result.errors.none { it.contains("Steuernummer") },
            "pre-snapshot fallback must not raise a tax-id error; got: ${result.errors}",
        )
    }

    @Test
    fun `a PRE-SNAPSHOT closing whose live row has no tax id is still rejected`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosing(shop)

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, ex.httpStatus)
        Unit
    }

    // ================================================================================
    // Round-7 D1 — canExport reflects a HARD error, not the mere presence of a warning
    // ================================================================================

    @Test
    fun `a hard error plus a warning still reports canExport = false (D1)`() {
        // seedClosing books a bill with NO TSE signature and NO VAT breakdown (two warnings) under
        // a taxless shop (a hard error). The old `errors.isEmpty() || warnings.isNotEmpty()`
        // reported canExport = true here, so the client enabled the export button and let an
        // operator ship a formally invalid package on the strength of an unrelated warning.
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }
        seedClosing(shop)

        val result = runBlocking { service.validateExportData(request.startDate, request.endDate) }
        assertTrue(result.errors.isNotEmpty(), "the missing tax id is a hard error")
        assertTrue(result.warnings.isNotEmpty(), "the unsigned bill is a warning — the D1 trigger")
        assertEquals(
            false,
            result.canExport,
            "canExport must be false while ANY hard error is present, regardless of warnings (D1)",
        )
        assertEquals(false, result.isValid, "isValid was already computed correctly")
        Unit
    }

    // ================================================================================
    // Round-7 D2 — an empty range is 404 noDataInRange, NOT the 422 tax-id error
    // ================================================================================
    //
    // C4 moved the tax-id gate AFTER Z-Bon resolution (the § 14 Abs. 4 Nr. 2 requirement attaches
    // to a closing, not to the shop). A range containing NO closings therefore reports 404
    // noDataInRange, where the pre-C4 ordering — reading the live shop row first — returned 422
    // missingTaxId. Pin the new ordering so it cannot silently flip back.

    @Test
    fun `an empty range returns 404 noDataInRange, not the 422 tax-id error (D2)`() {
        // Taxless shop on purpose: under the OLD ordering this would 422 on the missing tax id even
        // with no closing for it to attach to. No closing is seeded, so the range is empty.
        transaction { TestData.createShopInfo(shopTaxNumber = null, ustIdNr = null) }

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertEquals(
            HttpStatusCode.NotFound,
            ex.httpStatus,
            "an empty range must be 404 noDataInRange — the tax-id gate runs only AFTER a closing " +
                "is resolved (C4); got ${ex.httpStatus}: ${ex.message}",
        )
        assertTrue(
            ex.message?.contains("Steuernummer") != true,
            "must be the no-data error, not the tax-id 422; got: ${ex.message}",
        )
        Unit
    }

    // ================================================================================
    // Round-7 D3 — createReport writes the snapshot columns all-or-nothing
    // ================================================================================

    @Test
    fun `createReport writes the tax-id snapshot TOGETHER with the name — no partial snapshot (D3)`() {
        // `stammdatenFor` keys on stammName: a closing with stammName set but stammStnr / stammUstId
        // NULL would make closingsMissingTaxId flag a closing whose shop DID carry a tax id — a
        // fixture can build that partial state and confuse the C4 gate. It is unreachable from
        // production because createReport applies the WHOLE snapshot atomically
        // (StammdatenSnapshot.applyTo). Pin the invariant so the gate may trust
        // „stammName != null ⇒ the tax id was snapshotted too", rather than merely defending against
        // the partial state in a fixture.
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "143/815/09876", ustIdNr = null) }
        seedClosingViaProducer(shop)

        transaction {
            val z = ZBon.all().first()
            assertNotNull(z.stammName, "createReport must snapshot the name")
            assertEquals(
                "143/815/09876",
                z.stammStnr,
                "the Steuernummer must be snapshotted TOGETHER with the name — a stammName-set / " +
                    "stammStnr-null partial snapshot cannot arise from createReport (D3)",
            )
        }
    }
}
