package org.example.project.features.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.features.document.domain.models.Document
import org.example.project.features.document.domain.service.DocumentManagementService
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.test.assertEquals

/**
 * `uploadDocument` builds the stored `metadata_json` for an operator-defined document type. The
 * happy path (a parseable `request.metadata`) already merges via `JsonPrimitive`, but the two
 * fallbacks — no metadata supplied, or metadata that fails to parse — used a string-interpolated
 * template instead.
 *
 * The type name reaching that template is allow-listed against
 * `getAdditionalDocumentTypes()`, but that list is itself operator free text: its validation
 * rejects blanks, over-long names and path separators, and says nothing about quotes. So a
 * document type named `Rech"nung` passes the allow-list and then breaks the template.
 */
@DisplayName("Document upload — a quoted document type cannot corrupt metadata_json")
class DocumentMetadataJsonInjectionTest {
    private val quotedType = "Rech\"nung"

    @Test
    fun `a quote in an additional document type round-trips into metadata_json`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { _ ->
            val root = File(System.getProperty("java.io.tmpdir"), "posdoc-${System.nanoTime()}")
            root.mkdirs()
            try {
                transaction {
                    ShopConfigurationTable.upsert {
                        it[ShopConfigurationTable.shopId] = 1L
                        it[ShopConfigurationTable.key] = ConfigKey.DOCUMENT_STORAGE_CONFIG.key
                        it[ShopConfigurationTable.value] =
                            buildJsonObject {
                                put("path", root.absolutePath)
                                put("maxUploadSizeMb", 10)
                                putJsonArray("additionalDocumentTypes") { add(quotedType) }
                            }
                        it[ShopConfigurationTable.description] = "test"
                        it[ShopConfigurationTable.updatedAt] = null
                    }
                }
                ShopConfigurationManager.refreshConfiguration()

                val admin = transaction { TestData.createAdmin(employeeNumber = "ADM777", username = "uploader") }
                val source = File(root, "source.pdf").apply { writeBytes("%PDF-1.4 fixture".toByteArray()) }

                val service = GlobalContext.get().get<DocumentManagementService>()
                val uploaded =
                    service.uploadDocument(
                        file = source,
                        request =
                            DocumentDto.UploadDocumentRequestDto(
                                filename = "source.pdf",
                                fileType = quotedType,
                                // No metadata supplied — this is the fallback branch that interpolated.
                                metadata = null,
                            ),
                        uploadedBy = transaction { admin.id.value },
                    )

                val stored = transaction { Document.findById(uploaded.id)?.metadataJson }
                val parsed = Json.parseToJsonElement(stored!!).jsonObject
                assertEquals(
                    quotedType,
                    parsed["additionalTypeName"]?.jsonPrimitive?.content,
                    "the document type name must survive into metadata_json verbatim",
                )
            } finally {
                root.deleteRecursively()
            }
        }
}
