package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.data.repository.ZBonRepositoryImpl
import org.example.project.features.zBon.domain.KassenStammdatenSnapshot
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.service.swVersionTestTerminalService
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
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-7 C3 — DSFinV-K Tz. 3.2 (p.23): „Zur Vermeidung von Redundanzen werden die Stammdaten für
 * jeden Kassenabschluss …" The Stammdaten are a property of the **Kassenabschluss**, not of the
 * current configuration.
 *
 * Round-5's KABS-2 snapshotted the CLOSING-level shop identity (cashpointclosing.csv,
 * location.csv) and stopped one file short: `cashregister.csv` (Stamm_Kassen) still read the live
 * `KASSEN_MELDUNG_CONFIG`, and `slaves.csv` (Stamm_Terminals) still read the live `terminals`
 * table. `KASSE_SW_VERSION` is additionally rewritten by `ServerManager.updateKassenSwVersion` on
 * **every server start**, so merely upgrading the binary changed the bytes of an already delivered
 * closing — invariant 10 (closing immutability) in `docs/audits/dsfinvk-tse/tracks/07-invariants.md`.
 *
 * **Join keys are deliberately NOT snapshotted.** `Z_KASSE_ID` is the primary-key component every
 * DSFinV-K file joins on, and this implementation derives it from `kasseSeriennr` — the very same
 * value `KASSE_SERIENNR` carries. Freezing `KASSE_SERIENNR` per closing while `Z_KASSE_ID` stayed
 * live would desynchronise the two and break every cross-file join. `Z_NR`, `Z_ERSTELLUNG` and
 * `TERMINAL_ID` are join keys for the same reason; `KASSE_BASISWAEH_CODE` is a literal and
 * `KEINE_UST_ZUORDNUNG` is already derived from the closing's own rows.
 */
@DisplayName("DSFinV-K per-closing Kassen/Terminal Stammdaten snapshot (round-7 C3)")
class DsfinvkKassenStammdatenSnapshotTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    private val terminalService = swVersionTestTerminalService()

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    // ---------------------------------------------------------------- producers

    private fun kassenConfigJson(
        seriennr: String = "SN-TEST-0001",
        brand: String = "Alt Kassen AG",
        modell: String = "Alt Modell 1",
        swBrand: String = "Alt Software GmbH",
        swVersion: String = "1.0.0",
    ) = """{"kasseSeriennr":"$seriennr","kasseBrand":"$brand","kasseModell":"$modell",""" +
        """"kasseSwBrand":"$swBrand","kasseSwVersion":"$swVersion",""" +
        """"artDesAufzeichnungssystems":"Computergestützte/PC-Kassensysteme","inbetriebnahme":"2020-01-01"}"""

    /** [ConfigurationRepository.setSetting] is suspend but needs an AMBIENT Exposed transaction. */
    private fun writeKassenConfig(json: String) =
        transaction {
            val shopId =
                ShopInfo
                    .all()
                    .first()
                    .id.value
            runBlocking {
                ConfigurationRepository().setSetting(
                    shopId = shopId,
                    configKey = ConfigKey.KASSEN_MELDUNG_CONFIG,
                    value = json,
                )
            }
        }

    private fun seedTerminal(
        deviceId: String,
        clientId: String,
        brand: String = "Alt Terminal AG",
        model: String = "Alt Tablet",
        serial: String = "T-SER-1",
        swBrand: String = "Alt Client GmbH",
        swVersion: String = "1.0.0",
    ): Long =
        transaction {
            val now = currentTimeStamp()
            Terminal
                .new {
                    this.deviceId = deviceId
                    this.clientId = clientId
                    this.terminalBrand = brand
                    this.terminalModel = model
                    this.terminalSerialNr = serial
                    this.terminalSwBrand = swBrand
                    this.terminalSwVersion = swVersion
                    this.registeredAt = now
                    this.lastSeenAt = now
                }.id.value
        }

    /** Seeds a closing through the REAL producer, so any snapshot is written by production code. */
    private fun seedClosingViaProducer(): Long =
        transaction {
            val admin =
                Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                    ?: TestData.createAdmin()
            val shop =
                ShopInfo.all().firstOrNull()
                    ?: TestData.createShopInfo(shopTaxNumber = "143/815/09876")
            TestData.createTableWithSession("C3")
            ZBonRepositoryImpl().createReport(employee = admin, shopInfo = shop).id.value
        }

    /** A closing built the way 30+ existing test files build one: no snapshot columns at all. */
    private fun seedClosingWithoutSnapshot(nummer: Long = 1L): Long =
        transaction {
            val admin =
                Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                    ?: TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo(shopTaxNumber = "143/815/09876")
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

    private fun seedShop() =
        transaction {
            TestData.createShopInfo(shopName = "Alt GmbH", shopTaxNumber = "143/815/09876")
        }

    // ---------------------------------------------------------------- Stamm_Kassen

    @Test
    fun `cashregister csv re-exports byte-identically after the config is rewritten (C3)`() {
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedClosingViaProducer()

        val before = cashregisterRow()
        assertEquals("Alt Kassen AG", before["KASSE_BRAND"])
        assertEquals("1.0.0", before["KASSE_SW_VERSION"])

        // The server restarts on a new binary (ServerManager.updateKassenSwVersion) and the
        // operator corrects the Kassenmeldung brand/model at the same time.
        writeKassenConfig(
            kassenConfigJson(brand = "Neu Kassen AG", modell = "Neu Modell 2", swVersion = "9.9.9"),
        )

        assertEquals(
            before,
            cashregisterRow(),
            "a delivered closing must re-export byte-identically after the Kassenmeldung config changes",
        )
    }

    @Test
    fun `KASSE_SW_VERSION survives the startup rewrite of an ALREADY closed closing (C3)`() {
        seedShop()
        writeKassenConfig(kassenConfigJson(swVersion = "1.0.0"))
        seedClosingViaProducer()

        // Exactly what ServerManager.updateKassenSwVersion does on every start.
        writeKassenConfig(kassenConfigJson(swVersion = "2.5.0"))

        assertEquals(
            "1.0.0",
            cashregisterRow()["KASSE_SW_VERSION"],
            "a closing signed under software version X must keep reporting X",
        )
    }

    @Test
    fun `KASSE_SERIENNR is a join key and tracks Z_KASSE_ID rather than being snapshotted`() {
        // Z_KASSE_ID is derived from kasseSeriennr and is the primary-key component every file
        // joins on. Freezing KASSE_SERIENNR per closing while Z_KASSE_ID stayed live would
        // desynchronise them and break the joins — round 6 flagged this explicitly.
        seedShop()
        writeKassenConfig(kassenConfigJson(seriennr = "SN-TEST-0001"))
        seedClosingViaProducer()

        assertEquals("SN-TEST-0001", cashregisterRow()["KASSE_SERIENNR"])

        writeKassenConfig(kassenConfigJson(seriennr = "SN-TEST-0002"))
        val after = cashregisterRow()
        assertEquals(
            after["Z_KASSE_ID"],
            after["KASSE_SERIENNR"],
            "KASSE_SERIENNR must never diverge from the Z_KASSE_ID join key",
        )
        assertEquals("SN-TEST-0002", after["KASSE_SERIENNR"])
    }

    @Test
    fun `a closing with no kassen snapshot falls back to the live config`() {
        // Load-bearing for the 30+ test files that construct ZBon.new{} directly, and the honest
        // representation for every closing taken before this fix.
        seedShop()
        writeKassenConfig(kassenConfigJson(brand = "Live Kassen AG", swVersion = "7.7.7"))
        seedClosingWithoutSnapshot()

        assertNull(
            transaction { ZBon.all().first().stammKasseSnapshotAt },
            "fixture must genuinely have no kassen snapshot, else this test proves nothing",
        )
        val row = cashregisterRow()
        assertEquals("Live Kassen AG", row["KASSE_BRAND"])
        assertEquals("7.7.7", row["KASSE_SW_VERSION"])
    }

    // ---------------------------------------------------------------- Stamm_Terminals

    @Test
    fun `slaves csv re-exports byte-identically after terminal master data changes (C3)`() {
        seedShop()
        writeKassenConfig(kassenConfigJson())
        val terminalId = seedTerminal(deviceId = "dev-1", clientId = "T001")
        seedClosingViaProducer()

        val before = slavesRows()
        assertEquals(1, before.size)
        assertEquals("Alt Terminal AG", before.single()["TERMINAL_BRAND"])

        // The tablet is re-branded, its client is upgraded, and a SECOND terminal is registered
        // long after this closing was taken.
        transaction {
            Terminal.findById(terminalId)!!.apply {
                terminalBrand = "Neu Terminal AG"
                terminalSwVersion = "9.9.9"
            }
        }
        seedTerminal(deviceId = "dev-2", clientId = "T002")

        assertEquals(
            before,
            slavesRows(),
            "a delivered closing must re-export the terminals that existed WHEN IT WAS TAKEN",
        )
    }

    @Test
    fun `a closing with no kassen snapshot falls back to the live terminals`() {
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedTerminal(deviceId = "dev-1", clientId = "T001", brand = "Live Terminal AG")
        seedClosingWithoutSnapshot()

        assertEquals("Live Terminal AG", slavesRows().single()["TERMINAL_BRAND"])
    }

    @Test
    fun `a closing taken with no terminals snapshots an EMPTY set, not the terminals added later`() {
        // Presence-of-child-rows cannot be the discriminator: zero terminals is a legitimate
        // closing state. The dedicated stammKasseSnapshotAt marker is what distinguishes
        // "snapshotted, and there were none" from "never snapshotted".
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedClosingViaProducer()

        assertTrue(slavesRows().isEmpty())
        seedTerminal(deviceId = "dev-1", clientId = "T001")

        assertTrue(
            slavesRows().isEmpty(),
            "a terminal registered after the closing must not appear in it",
        )
    }

    // ---------------------------------------------------------------- freeze on rewrite

    @Test
    fun `the startup sw-version rewrite freezes the PRE-rewrite Kassen Stammdaten (C3)`() {
        // Closings taken before this fix have no snapshot and render through the live-row
        // fallback — i.e. exactly the bytes they already exported. The startup rewrite is the last
        // moment at which those bytes are still knowable, so it must freeze them, mirroring the
        // round-6 C5 freeze-on-edit in ShopInfoServiceImpl.updateShopInfo.
        seedShop()
        writeKassenConfig(kassenConfigJson(swVersion = "1.0.0", brand = "Alt Kassen AG"))
        seedTerminal(deviceId = "dev-1", clientId = "T001")
        seedClosingWithoutSnapshot()

        val before = cashregisterRow()
        val beforeSlaves = slavesRows()

        transaction { KassenStammdatenSnapshot.freezeUnsnapshottedClosings(newSwVersion = "2.0.0") }
        writeKassenConfig(kassenConfigJson(swVersion = "2.0.0", brand = "Alt Kassen AG"))

        assertEquals("1.0.0", cashregisterRow()["KASSE_SW_VERSION"])
        assertEquals(before, cashregisterRow(), "the pre-rewrite bytes must survive the rewrite")
        assertEquals(beforeSlaves, slavesRows())
    }

    @Test
    fun `the freeze is a no-op when the running version already matches`() {
        seedShop()
        writeKassenConfig(kassenConfigJson(swVersion = "1.0.0"))
        seedClosingWithoutSnapshot()

        transaction { KassenStammdatenSnapshot.freezeUnsnapshottedClosings(newSwVersion = "1.0.0") }

        assertNull(
            transaction { ZBon.all().first().stammKasseSnapshotAt },
            "no version change means nothing is about to be lost — leave the honest NULL alone",
        )
    }

    @Test
    fun `the freeze NEVER overwrites a kassen snapshot that is already present`() {
        seedShop()
        writeKassenConfig(kassenConfigJson(swVersion = "1.0.0"))
        seedClosingViaProducer()

        transaction { KassenStammdatenSnapshot.freezeUnsnapshottedClosings(newSwVersion = "3.0.0") }

        assertEquals(
            "1.0.0",
            transaction { ZBon.all().first().stammKasseSwVersion },
            "a snapshot taken at closing time is fiscal data — the freeze must not rewrite it",
        )
    }

    @Test
    fun `an edit to the shop identity does NOT touch the kassen snapshot, and vice versa`() {
        // The two snapshots are independent fiscal facts with independent freeze triggers. Guarding
        // against D3's "partially snapshotted" confusion: each group has its OWN discriminator and
        // is written all-or-nothing.
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedClosingWithoutSnapshot()

        transaction { KassenStammdatenSnapshot.freezeUnsnapshottedClosings(newSwVersion = "2.0.0") }

        transaction {
            val zBon = ZBon.all().first()
            assertNotEquals(null, zBon.stammKasseSnapshotAt)
            assertNull(zBon.stammName, "the kassen freeze must not stamp the shop identity")
        }
    }

    // ---------------------------------------------------------------- terminal sw-version refresh

    @Test
    fun `a terminal reporting a new app version leaves an already-taken closing snapshot untouched`() {
        // An order station never logs in, so opening its notification stream is what moves
        // terminal_sw_version after an app update. The closing taken before the update was signed
        // under the old client and must keep reporting it.
        seedShop()
        writeKassenConfig(kassenConfigJson())
        val terminalId = seedTerminal(deviceId = "dev-1", clientId = "T001", swVersion = "1.0.0")
        seedClosingViaProducer()

        val before = slavesRows()
        assertEquals("1.0.0", before.single()["TERMINAL_SW_VERSION"])
        val snapshotAt = transaction { ZBon.all().first().stammKasseSnapshotAt }

        runBlocking { terminalService.recordReportedSwVersion("dev-1", "1.1.0") }

        assertEquals(
            "1.1.0",
            transaction { Terminal.findById(terminalId)!!.terminalSwVersion },
            "fixture: the live column must really have moved, else this test proves nothing",
        )
        assertEquals(before, slavesRows(), "a delivered closing must keep the TERMINAL_SW_VERSION it was taken under")
        assertEquals(
            snapshotAt,
            transaction { ZBon.all().first().stammKasseSnapshotAt },
            "the refresh must not re-apply an existing snapshot",
        )
    }

    @Test
    fun `a terminal version refresh first freezes a closing that has no snapshot yet`() {
        // A pre-C3 closing renders slaves.csv from the live terminals table, so moving the live
        // version would rewrite its bytes — the refresh must freeze it first.
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedTerminal(deviceId = "dev-1", clientId = "T001", swVersion = "1.0.0")
        seedClosingWithoutSnapshot()

        val before = slavesRows()
        assertEquals("1.0.0", before.single()["TERMINAL_SW_VERSION"])

        runBlocking { terminalService.recordReportedSwVersion("dev-1", "1.1.0") }

        assertEquals(before, slavesRows(), "the pre-refresh bytes must survive the refresh")
        assertNotEquals(null, transaction { ZBon.all().first().stammKasseSnapshotAt })
    }

    @Test
    fun `characterisation - the terminal freeze stores the pre-rewrite values while the row takes the new one`() {
        // Pinned before the freeze moved behind TerminalFiscalGateway (B2-1a Task 11): the snapshot
        // child row must hold the OLD version, which it can only do if the freeze ran BEFORE the
        // terminal row was rewritten, in the same transaction.
        seedShop()
        writeKassenConfig(kassenConfigJson())
        val terminalId = seedTerminal(deviceId = "dev-1", clientId = "T001", swVersion = "1.0.0")
        val zBonId = seedClosingWithoutSnapshot()

        runBlocking { terminalService.recordReportedSwVersion("dev-1", "1.1.0") }

        transaction {
            val zBon = ZBon.findById(zBonId)!!
            assertNotEquals(null, zBon.stammKasseSnapshotAt)
            val frozen = zBon.stammTerminals.single()
            assertEquals("T001", frozen.terminalId)
            assertEquals("1.0.0", frozen.terminalSwVersion, "the snapshot carries the version from before the rewrite")
            assertEquals("Alt Terminal AG", frozen.terminalBrand)
            assertEquals("1.1.0", Terminal.findById(terminalId)!!.terminalSwVersion, "the live row carries the reported version")
        }
    }

    @Test
    fun `an unchanged terminal version does not freeze a closing that has no snapshot`() {
        seedShop()
        writeKassenConfig(kassenConfigJson())
        seedTerminal(deviceId = "dev-1", clientId = "T001", swVersion = "1.0.0")
        seedClosingWithoutSnapshot()

        runBlocking { terminalService.recordReportedSwVersion("dev-1", "1.0.0") }

        assertNull(
            transaction { ZBon.all().first().stammKasseSnapshotAt },
            "no version change means nothing is about to be lost — leave the honest NULL alone",
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(
                DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
            )
        }

    private fun cashregisterRow(): Map<String, String> = readRows(export(), "cashregister.csv").single()

    private fun slavesRows(): List<Map<String, String>> = readRows(export(), "slaves.csv")

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
