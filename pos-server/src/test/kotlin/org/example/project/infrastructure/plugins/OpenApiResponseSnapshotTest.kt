package org.example.project.infrastructure.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.testutils.Snapshots
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what each operation of `/openapi-generated.json` says it returns, one sorted line per operation and
 * status: `METHOD path status -> <media type> <schema>` (`$ref <Name>`, the inline object's property names,
 * `array of ...`, the JSON type, or `none` for a response without a body), plus one `components.schemas <Name>`
 * line per component schema.
 *
 * The sibling [OpenApiPathSnapshotTest] pins only the operation set. That missed a real loss in Stage B2-1b:
 * the Ktor compiler plugin infers a route's response bodies from the `respond(...)` calls it can see, and only
 * inside the module it compiles, so when `requirePermissionOrDefer` moved to `:platform-server` three
 * operations kept their paths but silently lost their 202/403 bodies (and `ErrorResponse` left
 * `components.schemas`). The snapshot is `openapi-responses.snapshot.txt`; see [Snapshots] for re-recording.
 */
class OpenApiResponseSnapshotTest {
    private companion object {
        /** Operation keys in a path item; everything else there is metadata, not an endpoint. */
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")
        const val REF_PREFIX = "#/components/schemas/"
    }

    @Test
    fun `generated spec documents the same response bodies`() =
        testApp { client ->
            TestData.setApiDocsEnabled(true)
            val body =
                try {
                    val response = client.get("/openapi-generated.json")
                    assertEquals(HttpStatusCode.OK, response.status)
                    response.bodyAsText()
                } finally {
                    TestData.setApiDocsEnabled(false)
                }
            val document = Json.parseToJsonElement(body).jsonObject
            Snapshots.assertMatches("openapi-responses.snapshot.txt", (responseLines(document) + schemaLines(document)).sorted())
        }

    private fun responseLines(document: JsonObject): List<String> =
        document["paths"]!!.jsonObject.entries.flatMap { (path, item) ->
            item.jsonObject.entries
                .filter { (method, _) -> method in HTTP_METHODS }
                .flatMap { (method, operation) ->
                    val responses = operation.jsonObject["responses"]?.jsonObject ?: JsonObject(emptyMap())
                    responses.entries.map { (status, response) ->
                        "${method.uppercase()} $path $status -> ${bodyOf(response.jsonObject)}"
                    }
                }
        }

    private fun schemaLines(document: JsonObject): List<String> =
        document["components"]
            ?.jsonObject
            ?.get("schemas")
            ?.jsonObject
            ?.keys
            .orEmpty()
            .map { "components.schemas $it" }

    private fun bodyOf(response: JsonObject): String {
        val content = response["content"]?.jsonObject ?: return "none"
        if (content.isEmpty()) return "none"
        return content.entries
            .sortedBy { it.key }
            .joinToString(" | ") { (mediaType, media) -> "$mediaType ${schemaOf(media.jsonObject["schema"])}" }
    }

    private fun schemaOf(schema: JsonElement?): String {
        val node = schema as? JsonObject ?: return "none"
        node["\$ref"]?.let { return "\$ref ${it.jsonPrimitive.content.removePrefix(REF_PREFIX)}" }
        node["properties"]?.let { properties ->
            return properties.jsonObject.keys
                .sorted()
                .joinToString(",", "{", "}")
        }
        node["items"]?.let { return "array of ${schemaOf(it)}" }
        return node["type"]?.toString() ?: "inline"
    }
}
