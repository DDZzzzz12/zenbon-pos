package org.example.project.features.tse.findings

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.tse.TseExplanationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TseFindingServiceTest {
    private val serial = "2D2F88FBC30BBC9ACEB75C28E212105996786C7A0A08B2C6CF74306EDC75EC48"
    private val otherSerial = "AB".repeat(32)
    private lateinit var service: TseFindingService

    private fun explain(
        from: Long,
        to: Long,
        text: String,
        supersedes: Long? = null,
    ): TseExplanationDto.ExplanationDto =
        runBlocking {
            service.explain(
                TseExplanationDto.CreateRequestDto(serial, from, to, TseExplanationCategory.OTHER, text, supersedesId = supersedes),
                "7",
            )
        }

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        service = TseFindingService(TseTanrRepository(), TseExplanationRepository())
        transaction {
            for (n in listOf(1L, 2L, 6L)) {
                TseTransaction.new {
                    tseSerialNumber = serial
                    tseSerialHex = serial
                    transactionNumber = n
                    tseMode = TseMode.NORMAL
                    startTime = currentTimeStamp()
                }
            }
        }
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    @Test
    fun `an unexplained gap is reported without an explanation`() {
        val response = runBlocking { service.findings(from = null, to = null) }
        val gap = response.findings.single()
        assertEquals(3L to 5L, gap.tanrFrom to gap.tanrTo)
        assertNull(gap.explanationId)
    }

    @Test
    fun `an explanation covering the range marks the gap explained`() {
        val created =
            runBlocking {
                service.explain(
                    TseExplanationDto.CreateRequestDto(serial, 3, 5, TseExplanationCategory.TSE_OUTAGE, "Stromausfall 21.09."),
                    employeeId = "7",
                )
            }
        assertEquals(TseExplanationSource.OPERATOR, created.source)
        assertEquals(created.id, runBlocking { service.findings(null, null) }.findings.single().explanationId)
    }

    @Test
    fun `a correction replaces the explanation it names, and the original stays stored`() {
        val first =
            runBlocking {
                service.explain(TseExplanationDto.CreateRequestDto(serial, 3, 5, TseExplanationCategory.OTHER, "erst"), "7")
            }
        val second =
            runBlocking {
                service.explain(
                    TseExplanationDto.CreateRequestDto(serial, 3, 5, TseExplanationCategory.TSE_OUTAGE, "korrigiert", supersedesId = first.id),
                    "7",
                )
            }

        val response = runBlocking { service.findings(null, null) }
        assertEquals(listOf(second.id), response.explanations.map { it.id }, "only the current explanation is listed")
        assertNotNull(runBlocking { service.explanation(first.id) }, "append-only: the original is never deleted")
    }

    /**
     * E2E round 4, R4-5: the register's export was built from the current entries, so the first text
     * of a corrected explanation was missing from the file an auditor receives. The response now
     * carries the whole register, in write order.
     */
    @Test
    fun `the history carries a superseded entry next to its correction`() {
        val first = explain(3, 5, "erst")
        val second = explain(3, 5, "korrigiert", supersedes = first.id)

        val history = runBlocking { service.findings(null, null) }.history

        assertEquals(listOf(first.id, second.id), history.map { it.id })
        assertEquals(first.id, history.last().supersedesId)
    }

    @Test
    fun `a correction naming an entry that does not exist is refused`() {
        val refusal =
            assertFailsWith<PosException> {
                runBlocking {
                    service.explain(
                        TseExplanationDto.CreateRequestDto(serial, 3, 5, TseExplanationCategory.OTHER, "x", supersedesId = 9999),
                        "7",
                    )
                }
            }
        assertTrue(refusal.message!!.contains("9999"), "the refusal names the id it could not find: ${refusal.message}")
        assertEquals(0, runBlocking { service.findings(null, null) }.explanations.size)
    }

    @Test
    fun `a correction naming an entry another correction already replaced is refused`() {
        val first = explain(3, 5, "erst")
        val second = explain(3, 5, "korrigiert", supersedes = first.id)

        val refusal =
            assertFailsWith<PosException> { explain(3, 5, "nochmal", supersedes = first.id) }
        assertTrue(
            refusal.message!!.contains("superseded", ignoreCase = true) || refusal.message!!.contains("replaced", ignoreCase = true),
            "the refusal says the entry was already replaced: ${refusal.message}",
        )
        assertEquals(listOf(second.id), runBlocking { service.findings(null, null) }.explanations.map { it.id })
    }

    @Test
    fun `a correction for a different range is refused, so it cannot silently hide an unrelated entry`() {
        // The register's whole value is that it reads correctly: `current()` drops every id named
        // by any supersedesId, so one mistyped id would remove a still-valid explanation of some
        // other range from the auditor's view and flip its finding back to "not explained".
        val other = explain(3, 5, "Stromausfall")

        assertFailsWith<PosException> { explain(7, 9, "anderer Bereich", supersedes = other.id) }
        assertEquals(listOf(other.id), runBlocking { service.findings(null, null) }.explanations.map { it.id })
    }

    @Test
    fun `a correction for a different device is refused`() {
        val other = explain(3, 5, "Stromausfall")

        val refusal =
            assertFailsWith<PosException> {
                runBlocking {
                    service.explain(
                        TseExplanationDto.CreateRequestDto(
                            "BB11".repeat(16),
                            3,
                            5,
                            TseExplanationCategory.OTHER,
                            "fremdes Gerät",
                            supersedesId = other.id,
                        ),
                        "7",
                    )
                }
            }
        assertTrue(refusal.message!!.contains("device", ignoreCase = true) || refusal.message!!.contains("range", ignoreCase = true))
    }

    @Test
    fun `a chain of corrections leaves only the last entry current`() {
        val a = explain(3, 5, "A")
        val b = explain(3, 5, "B", supersedes = a.id)
        val c = explain(3, 5, "C", supersedes = b.id)

        val response = runBlocking { service.findings(null, null) }
        assertEquals(listOf(c.id), response.explanations.map { it.id })
        assertEquals(c.id, response.findings.single().explanationId)
        // Append-only: every earlier link of the chain is still stored and readable.
        assertNotNull(runBlocking { service.explanation(a.id) })
        assertNotNull(runBlocking { service.explanation(b.id) })
    }

    @Test
    fun `an explanation covers only the device it names`() {
        // Same TANR range, different TSE: the serial is part of the identity of a finding, so a
        // second device's identical gap stays unexplained.
        transaction {
            TseTransaction.new {
                tseSerialNumber = otherSerial
                tseSerialHex = otherSerial
                transactionNumber = 1L
                tseMode = TseMode.NORMAL
                startTime = currentTimeStamp()
            }
            TseTransaction.new {
                tseSerialNumber = otherSerial
                tseSerialHex = otherSerial
                transactionNumber = 6L
                tseMode = TseMode.NORMAL
                startTime = currentTimeStamp()
            }
        }
        val mine = explain(2, 5, "nur dieses Gerät")

        val findings = runBlocking { service.findings(null, null) }.findings
        assertEquals(2, findings.size, "both devices have the same gap")
        assertEquals(mine.id, findings.single { it.tseSerialHex == serial }.explanationId)
        assertNull(findings.single { it.tseSerialHex == otherSerial }.explanationId, "the other device stays unexplained")
    }

    @Test
    fun `an invalid explanation is refused`() {
        assertFailsWith<PosException> {
            runBlocking { service.explain(TseExplanationDto.CreateRequestDto(serial, 5, 3, TseExplanationCategory.OTHER, "x"), "7") }
        }
        Unit
    }

    /**
     * The register is evidence only while it cannot be rewritten (design 2026-09-22 D7): a
     * correction is a new row naming the old one. Pinned by reflection so an added `update`/
     * `delete` fails the build, not only by the behavioural test above.
     */
    @Test
    fun `the explanation repository exposes no update and no delete`() {
        val forbidden =
            TseExplanationRepository::class
                .members
                .map { it.name }
                .filter { name -> listOf("update", "delete", "remove", "edit").any { name.startsWith(it, ignoreCase = true) } }
        assertEquals(emptyList(), forbidden, "tse_explanation is append-only")
    }
}
