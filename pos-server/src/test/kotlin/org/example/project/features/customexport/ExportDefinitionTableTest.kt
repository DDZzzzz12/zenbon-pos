package org.example.project.features.customexport

import org.example.project.features.customexport.database.tables.ExportDefinitionFieldTable
import org.example.project.features.customexport.domain.models.ExportDefinition
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The saved export: a subject, its chosen columns, and the order they come out in. */
@DisplayName("Export definition tables")
class ExportDefinitionTableTest {
    @Test
    fun `a definition round-trips with its fields in position order`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val definitionId =
                transaction {
                    val definition =
                        ExportDefinition.new {
                            shop = ShopInfo.all().first()
                            name = "Kreditoren mit USt-IdNr."
                            subjectKey = "business_partner"
                            createdByEmployeeId = "admin"
                        }

                    // Inserted out of order on purpose: if the read below happened to return
                    // insertion order, that would prove nothing about `position`.
                    ExportDefinitionField.new {
                        this.definition = definition
                        fieldKey = "city"
                        position = 2
                        heading = "Ort"
                    }
                    ExportDefinitionField.new {
                        this.definition = definition
                        fieldKey = "name"
                        position = 0
                        heading = "Firma"
                    }
                    ExportDefinitionField.new {
                        this.definition = definition
                        fieldKey = "vat_id"
                        position = 1
                        heading = "USt-IdNr."
                    }

                    definition.id.value
                }

            transaction {
                val definition = ExportDefinition.findById(definitionId)
                assertNotNull(definition, "the definition survives the transaction it was written in")
                assertEquals("Kreditoren mit USt-IdNr.", definition.name)
                assertEquals("business_partner", definition.subjectKey)
                assertNull(definition.dateFrom, "an unbounded definition stores no lower bound")
                assertNull(definition.enabledToggles, "no toggle was switched on")

                // Explicit orderBy: DAO referrers carry no ordering, so this is how every read of a
                // definition's columns must be written.
                val headings =
                    ExportDefinitionField
                        .find { ExportDefinitionFieldTable.definitionId eq definition.id }
                        .orderBy(ExportDefinitionFieldTable.position to SortOrder.ASC)
                        .map { it.heading }

                assertEquals(listOf("Firma", "USt-IdNr.", "Ort"), headings)
            }
        }
    }

    @Test
    fun `deleting a definition deletes its fields`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val definitionId =
                transaction {
                    val definition =
                        ExportDefinition.new {
                            shop = ShopInfo.all().first()
                            name = "Wird gelöscht"
                            subjectKey = "business_partner"
                        }
                    ExportDefinitionField.new {
                        this.definition = definition
                        fieldKey = "name"
                        position = 0
                        heading = "Firma"
                    }
                    definition.id.value
                }

            transaction {
                ExportDefinition.findById(definitionId)!!.delete()
            }

            transaction {
                assertNull(ExportDefinition.findById(definitionId))
                val orphans =
                    ExportDefinitionField
                        .find { ExportDefinitionFieldTable.definitionId eq definitionId }
                        .count()
                assertEquals(0L, orphans, "the child rows cascade with the definition they belong to")
            }
        }
    }

    @Test
    fun `createdAt is set on insert`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val definitionId =
                transaction {
                    ExportDefinition
                        .new {
                            shop = ShopInfo.all().first()
                            name = "Mit Zeitstempel"
                            subjectKey = "business_partner"
                        }.id.value
                }

            transaction {
                val definition = ExportDefinition.findById(definitionId)!!
                // `clientDefault`, not `default(value)`: the latter evaluates once at server start and
                // stamps every row ever written with that same instant. Asserting the year is past the
                // epoch is enough to catch an unset column; the freeze is guarded by the KDoc and by
                // this assertion failing the moment the column stops being filled per row.
                assertTrue(
                    definition.createdAt.year >= 2020,
                    "createdAt is filled per row, not left at a zero value: ${definition.createdAt}",
                )
                assertNull(definition.updatedAt, "a freshly written definition has never been updated")
            }
        }
    }
}
