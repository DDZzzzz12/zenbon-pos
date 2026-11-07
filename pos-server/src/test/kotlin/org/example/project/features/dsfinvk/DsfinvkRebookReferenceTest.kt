package org.example.project.features.dsfinvk

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.websocket.NotificationHub
import org.example.project.features.bill.RebookFixture
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals

/**
 * A Zahlartwechsel (Storno + Neubuchung) in `references.csv`: the Storno names the original it
 * reverses, and the Neubuchung names the stornoed original it replaces (Anhang E, REF_TYP
 * «Transaktion»), so an auditor can follow the chain from either Beleg.
 */
@DisplayName("DSFinV-K — Zahlartwechsel references")
class DsfinvkRebookReferenceTest {
    private val deviceId = "dsfinvk-rebook-device"
    private val fixture = RebookFixture(deviceId)
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    /** H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation. */
    private fun aliasAdvisoryLock() =
        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
        }

    private fun stornoIdOf(billId: Long): Long =
        transaction {
            Bill
                .find { BillTable.deletedBillId eq billId }
                .single()
                .id.value
        }

    @Test
    fun `the Storno and the Neubuchung both reference the stornoed original`() =
        testApp(
            setupData = {
                TestData.setTseMode(TseMode.DISABLED)
                TestData.createAdmin()
            },
        ) { client ->
            val sale = fixture.closeSale(TipRecipient.EMPLOYEE)
            val rebooked =
                fixture.rebook(
                    sale,
                    BillDto.RebookRequestDto(
                        payments = listOf(PaymentDto.PaymentRequest(amount = "25.00", paymentTypeId = sale.cardTypeId)),
                        tips = "2.00",
                    ),
                )
            aliasAdvisoryLock()
            val token = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val closing = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, closing.status, "body: ${closing.bodyAsText()}")

            val zip = service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))

            val original = "B-${sale.billId}"
            val refs = readRows(zip, "references.csv").filter { it["REF_BON_ID"] == original }
            assertEquals(
                setOf("B-${stornoIdOf(sale.billId)}", "B-${rebooked.billId}"),
                refs.map { it["BON_ID"] }.toSet(),
                "the Storno and the Neubuchung each point at the original, got $refs",
            )
            assertEquals(2, refs.size, "exactly two references to the original, got $refs")
            refs.forEach { ref ->
                assertEquals("Transaktion", ref["REF_TYP"])
                assertEquals(ref["Z_NR"], ref["REF_Z_NR"], "one closing holds the original and both Belege")
                assertEquals(ref["Z_ERSTELLUNG"], ref["REF_DATUM"], "REF_DATUM is the referenced closing's timestamp")
            }
        }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

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
                    if (lines.size < 2) return emptyList()
                    val header = splitCsvLine(lines.first())
                    return lines.drop(1).map { header.zip(splitCsvLine(it)).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found in export zip")
    }
}
