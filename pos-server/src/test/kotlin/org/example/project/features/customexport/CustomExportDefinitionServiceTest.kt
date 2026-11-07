package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.subjects.PartnerSubject
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Saving and loading a custom export definition.
 *
 * The two cases that carry weight are the capability check on save and the broken-key report on
 * load. The rest is hygiene — refusing requests that cannot mean anything.
 */
@DisplayName("Custom export definitions")
class CustomExportDefinitionServiceTest {
    private val service = CustomExportServiceImpl()

    /** Everything the `partner` subject's fields require. */
    private val held = setOf(Capability.CREDITOR_READ)

    private fun request(
        name: String = "Kreditoren mit USt-IdNr.",
        subjectKey: String = "partner",
        fields: List<CustomExportDto.DefinitionFieldDto> =
            listOf(
                CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                CustomExportDto.DefinitionFieldDto("partner.vat_id", 1, "USt-IdNr."),
                CustomExportDto.DefinitionFieldDto("partner.city", 2, "Ort"),
            ),
        enabledToggles: List<String> = emptyList(),
    ) = CustomExportDto.SaveDefinitionRequestDto(
        name = name,
        subjectKey = subjectKey,
        fields = fields,
        enabledToggles = enabledToggles,
    )

    @Test
    fun `a saved definition loads back with its columns in position order`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val saved =
                    service.save(
                        request =
                            request(
                                // Handed over out of order: if the read happened to return insertion
                                // order this assertion would prove nothing about `position`.
                                fields =
                                    listOf(
                                        CustomExportDto.DefinitionFieldDto("partner.city", 2, "Ort"),
                                        CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                                        CustomExportDto.DefinitionFieldDto("partner.vat_id", 1, "USt-IdNr."),
                                    ),
                                enabledToggles = listOf("partner.active_only"),
                            ),
                        held = held,
                        employeeId = "admin",
                    )

                val loaded = service.get(saved.id)
                assertNotNull(loaded, "a definition that was just saved is readable by its id")
                assertEquals("Kreditoren mit USt-IdNr.", loaded.name)
                assertEquals("partner", loaded.subjectKey)
                assertEquals(listOf("partner.active_only"), loaded.enabledToggles)
                assertEquals(
                    listOf("partner.name", "partner.vat_id", "partner.city"),
                    loaded.fields.map { it.fieldKey },
                )
                assertEquals(listOf("Firma", "USt-IdNr.", "Ort"), loaded.fields.map { it.heading })
                assertFalse(loaded.isBroken, "every key resolves against the catalogue that saved it")
                assertTrue(loaded.unknownKeys.isEmpty())

                assertEquals(listOf(saved.id), service.list().map { it.id })
            }
        }
    }

    @Test
    fun `a definition with no fields is rejected`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                // An export of nothing is a mistake, not a request.
                assertFailsWithPosException {
                    service.save(request(fields = emptyList()), held, "admin")
                }
            }
        }
    }

    @Test
    fun `a field key the catalogue does not know is rejected at save time`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                assertFailsWithPosException {
                    service.save(
                        request(
                            fields =
                                listOf(CustomExportDto.DefinitionFieldDto("partner.shoe_size", 0, "Schuhgröße")),
                        ),
                        held,
                        "admin",
                    )
                }
            }
        }
    }

    @Test
    fun `a field the caller may not see is rejected even though the client would not have offered it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                // The client filters the catalogue for convenience; this is the control. A caller
                // holding nothing gets nothing, however well-formed the request looks.
                assertFailsWithPosException {
                    service.save(request(), held = emptySet(), employeeId = "admin")
                }
            }
        }
    }

    @Test
    fun `an unknown subject key is rejected`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                assertFailsWithPosException {
                    service.save(request(subjectKey = "payroll"), held, "admin")
                }
            }
        }
    }

    @Test
    fun `a definition whose field left the catalogue loads as broken, naming the key`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val saved = service.save(request(), held, "admin")

                // The save path refuses a key the catalogue does not know, so the only way to build a
                // definition that has gone stale is to write the stale key straight into the table —
                // which is exactly what a catalogue change does to a definition saved last year.
                transaction {
                    ExportDefinitionField
                        .all()
                        .first { it.position == 1 }
                        .fieldKey = "partner.retired_column"
                }

                val loaded = service.get(saved.id)
                assertNotNull(loaded)
                assertTrue(loaded.isBroken, "a definition naming a key the catalogue lost cannot run")
                assertEquals(listOf("partner.retired_column"), loaded.unknownKeys)
                assertEquals(
                    3,
                    loaded.fields.size,
                    "the stale column is still reported, not silently dropped: a CSV quietly one " +
                        "column short is worse than no CSV",
                )
            }
        }
    }

    @Test
    fun `two fields claiming one position are rejected`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                // A definition is saved so the same extract comes out the same way next month.
                // Two columns at one slot leave that order to the database.
                assertFailsWithPosException {
                    service.save(
                        request(
                            fields =
                                listOf(
                                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                                    CustomExportDto.DefinitionFieldDto("partner.city", 0, "Ort"),
                                ),
                        ),
                        held,
                        "admin",
                    )
                }
            }
        }
    }

    @Test
    fun `positions need not be contiguous`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                // 0/2/5 is an unambiguous order and there is nothing wrong with it to refuse — the
                // guard above is about ambiguity, not about tidiness.
                val saved =
                    service.save(
                        request(
                            fields =
                                listOf(
                                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                                    CustomExportDto.DefinitionFieldDto("partner.city", 5, "Ort"),
                                    CustomExportDto.DefinitionFieldDto("partner.vat_id", 2, "USt-IdNr."),
                                ),
                        ),
                        held,
                        "admin",
                    )

                assertEquals(
                    listOf("partner.name", "partner.vat_id", "partner.city"),
                    service.get(saved.id)?.fields?.map { it.fieldKey },
                )
            }
        }
    }

    @Test
    fun `a date range against a subject with no date column is rejected`() {
        // Called directly, not through `save`: every subject in the catalogue today has a date
        // column, so no request can drive this branch. That is the reason to prove the guard now —
        // it exists for the second subject that arrives without one, and by then nobody will
        // remember to check.
        val undated =
            ExportSubject(
                key = "undated",
                label = "Ohne Datum",
                root = BusinessPartnerTable,
                dateColumn = null,
            )

        assertFailsWith<PosException> {
            service.requireDateFilterApplies(undated, dateFrom = "2026-01-01T00:00", dateTo = null)
        }
        assertFailsWith<PosException> {
            service.requireDateFilterApplies(undated, dateFrom = null, dateTo = "2026-12-31T23:59")
        }

        // No bound asked for, nothing to refuse.
        service.requireDateFilterApplies(undated, dateFrom = null, dateTo = null)
        // And the guard says nothing about a subject that does have a date column.
        service.requireDateFilterApplies(
            PartnerSubject.subject,
            dateFrom = "2026-01-01T00:00",
            dateTo = "2026-12-31T23:59",
        )
    }

    /**
     * `assertFailsWith` cannot take a suspending block, and a test body ending in one is silently
     * skipped by JUnit 5 anyway. Catching the throwable ourselves avoids both.
     */
    private suspend fun assertFailsWithPosException(block: suspend () -> Unit) {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue(thrown is PosException, "expected the request to be rejected, but got: $thrown")
    }
}
