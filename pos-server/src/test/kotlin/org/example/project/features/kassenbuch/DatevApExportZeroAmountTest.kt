package org.example.project.features.kassenbuch

import kotlinx.datetime.LocalDateTime
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One unexportable invoice must not take the whole period with it.
 *
 * A Nullrechnung is a real document — a warranty replacement, a free-of-charge delivery, a
 * correction that nets to zero — and capture must allow it. DATEV's schema forbids it in the
 * ledger (`p7` is `minInclusive 0.01`), but that is an **export** constraint, not a capture one.
 *
 * Before this guard, `DatevXmlFieldValidator.requireValidAmount` threw a plain
 * `IllegalArgumentException` — not a `PosException` — from inside `DatevApLedgerGenerator`'s fold,
 * so a single 0.00 invoice aborted the entire export run with a 500.
 *
 * Negative amounts are covered too: the validator's check is `compareTo(ZERO) != 0`, so a negative
 * passed it and reached DATEV as a schema-invalid value.
 */
@DisplayName("DATEV AP export — an unexportable amount skips one invoice, not the run")
class DatevApExportZeroAmountTest {
    private companion object {
        const val START_DATE = "2026-04-01"
        const val END_DATE = "2026-04-30"
    }

    private lateinit var adminId: UUID
    private val repo = DocumentRepositoryImpl()

    /** Creates the INVOICE document an invoice hangs off. The file itself never has to exist. */
    private fun invoiceDocument(filename: String): Long =
        repo
            .create(
                filename = filename,
                fileType = "INVOICE",
                filePath = "/nonexistent/$filename",
                fileSize = 1024L,
                uploadedAt = currentTimeStamp(),
                uploadedBy = adminId,
                contentHash = "hash-${System.nanoTime()}",
                mediaType = "application/pdf",
            ).id.value

    @Test
    fun `a zero-amount invoice is skipped and the rest of the period still exports`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value

                transaction {
                    repo.createInvoice(
                        sourceDocumentId = invoiceDocument("invoice-null.pdf"),
                        invoiceNumber = "INV-NULL-0001",
                        issueDate = LocalDateTime(2026, 4, 10, 0, 0),
                        currency = "EUR",
                        sellerName = "Nullrechnung GmbH",
                        taxInclusiveTotal = BigDecimal("0.00"),
                    )
                    repo.createInvoice(
                        sourceDocumentId = invoiceDocument("invoice-normal.pdf"),
                        invoiceNumber = "INV-NORMAL-0001",
                        issueDate = LocalDateTime(2026, 4, 11, 0, 0),
                        currency = "EUR",
                        sellerName = "Ganz Normal AG",
                        taxInclusiveTotal = BigDecimal("238.00"),
                    )
                }
            },
        ) { _ ->
            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<amount>238.00</amount>"),
                "the healthy invoice must still export; got:\n$xml",
            )
            assertFalse(
                xml.contains("<amount>0.00</amount>"),
                "DATEV rejects a 0.00 entry, so the Nullrechnung must be left out; got:\n$xml",
            )
            assertFalse(
                xml.contains("INV-NULL-0001"),
                "the skipped invoice must not appear in the ledger at all; got:\n$xml",
            )
        }
    }

    @Test
    fun `a negative-amount invoice is skipped too`() {
        testApp(
            setupData = {
                TestData.createShopInfo()
                adminId = TestData.createAdmin().id.value

                transaction {
                    repo.createInvoice(
                        sourceDocumentId = invoiceDocument("invoice-negative.pdf"),
                        invoiceNumber = "INV-NEGATIVE-0001",
                        issueDate = LocalDateTime(2026, 4, 12, 0, 0),
                        currency = "EUR",
                        sellerName = "Minusbetrag GmbH",
                        taxInclusiveTotal = BigDecimal("-50.00"),
                    )
                    repo.createInvoice(
                        sourceDocumentId = invoiceDocument("invoice-normal-2.pdf"),
                        invoiceNumber = "INV-NORMAL-0002",
                        issueDate = LocalDateTime(2026, 4, 13, 0, 0),
                        currency = "EUR",
                        sellerName = "Ganz Normal AG",
                        taxInclusiveTotal = BigDecimal("119.00"),
                    )
                }
            },
        ) { _ ->
            val xml = exportLedgerXml()

            assertTrue(
                xml.contains("<amount>119.00</amount>"),
                "the healthy invoice must still export; got:\n$xml",
            )
            assertFalse(
                xml.contains("-50.00"),
                "`p7` is minInclusive 0.01, so a negative entry is schema-invalid and must be " +
                    "skipped rather than emitted; got:\n$xml",
            )
        }
    }

    /** Runs the side-effect-free export and returns the concatenated per-invoice ledger XML. */
    private suspend fun exportLedgerXml(): String {
        val exportService = GlobalContext.get().get<DatevApExportService>()
        val result = exportService.exportTransient(START_DATE, END_DATE, adminId)

        val ledgerFiles = StringBuilder()
        ZipInputStream(result.bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.startsWith("Rechnungsdaten_")) {
                    ledgerFiles.append(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
            }
        }
        return ledgerFiles.toString()
    }
}
