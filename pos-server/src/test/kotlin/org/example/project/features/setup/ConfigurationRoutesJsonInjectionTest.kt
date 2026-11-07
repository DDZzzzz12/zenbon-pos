package org.example.project.features.setup

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * The shop-configuration PUTs used to build their stored JSON blob by interpolating operator
 * free text into a `"""{"..."}"""` template. A `"` in any of those fields ends the JSON string
 * literal early, and the result is one of two silent failures:
 *
 *  - the blob still parses, but with injected keys — a crafted value overrides a sibling setting;
 *  - the blob no longer parses, so `ConfigurationRepository.valueToJsonElement` falls into its
 *    catch and stores the whole thing as a JSON *string*. Every later `getJsonObject(...)` read
 *    then returns null and the entire config silently reverts to its defaults.
 *
 * A Windows path pasted with an escaped quote reaches the second case by accident. These tests
 * pin the serializer-based write path for each affected endpoint.
 */
@DisplayName("Configuration PUTs — operator free text cannot corrupt the stored JSON")
class ConfigurationRoutesJsonInjectionTest {
    /** Closes the JSON string, injects `"enabled":true`, and reopens it so the blob still parses. */
    private val injectingKassenName = "Kasse\",\"enabled\":true,\"x\":\""

    @Test
    fun `datev-xml - a quote in kassenName round-trips and cannot flip enabled`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val body =
                buildJsonObject {
                    put("kassenName", injectingKassenName)
                    // The request explicitly disables auto-export; the injected key tries to turn it on.
                    put("enabled", false)
                }.toString()

            val response =
                client.put("/api/v1/config/1/datev-xml") {
                    bearerAuth(token)
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, response.status)

            ShopConfigurationManager.refreshConfiguration()
            assertEquals(
                false,
                ShopConfigurationManager.getDatevAutoEnabled(),
                "the injected \"enabled\":true must not override the sibling the request actually sent",
            )
            assertEquals(
                injectingKassenName,
                ShopConfigurationManager.getDatevXmlKassenName(),
                "kassenName must survive the round-trip verbatim, quotes included",
            )
        }

    @Test
    fun `document-storage - a quote in path round-trips instead of silently reverting`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { client ->
            // A real, writable directory whose name contains a quote — the route rejects paths that
            // do not exist, so the accidental-trigger case needs a genuine one on disk.
            val root = File(System.getProperty("java.io.tmpdir"), "posconf-${System.nanoTime()}")
            val quoted = File(root, "ex\"port")
            quoted.mkdirs()
            try {
                val token = TestAuthHelper.loginAsAdmin(client)
                val body =
                    buildJsonObject {
                        put("path", quoted.absolutePath)
                        put("maxUploadSizeMb", 7)
                    }.toString()

                val response =
                    client.put("/api/v1/config/1/document-storage") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                ShopConfigurationManager.refreshConfiguration()
                assertEquals(
                    quoted.absolutePath,
                    ShopConfigurationManager.getDocumentStoragePath(),
                    "the configured path must persist, not fall back to the OS default",
                )
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `document-storage - a quote in a document type round-trips without losing path`() =
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { client ->
            // additionalDocumentTypes was interpolated element-by-element as "\"$it\"". The DTO's
            // validate() rejects blanks, path separators and >50 chars — but not a quote.
            val root = File(System.getProperty("java.io.tmpdir"), "posconf-${System.nanoTime()}")
            root.mkdirs()
            val quotedType = "Rech\"nung"
            try {
                val token = TestAuthHelper.loginAsAdmin(client)
                val body =
                    buildJsonObject {
                        put("path", root.absolutePath)
                        put("maxUploadSizeMb", 7)
                        putJsonArray("additionalDocumentTypes") { add(quotedType) }
                    }.toString()

                val response =
                    client.put("/api/v1/config/1/document-storage") {
                        bearerAuth(token)
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                ShopConfigurationManager.refreshConfiguration()
                assertEquals(
                    listOf(quotedType),
                    ShopConfigurationManager.getAdditionalDocumentTypes(),
                    "the document type must survive the round-trip verbatim",
                )
                assertEquals(
                    root.absolutePath,
                    ShopConfigurationManager.getDocumentStoragePath(),
                    "the sibling path must be unaffected by a quote in a document type",
                )
            } finally {
                root.deleteRecursively()
            }
        }
}
