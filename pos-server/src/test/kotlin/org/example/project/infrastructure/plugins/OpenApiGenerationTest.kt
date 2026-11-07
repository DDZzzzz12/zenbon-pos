package org.example.project.infrastructure.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the OpenAPI document the Ktor compiler plugin derives from the routing tree, which is
 * the only spec this server has since the hand-written `openapi/documentation.yaml` was deleted.
 *
 * The first test asserts the generator runs and produces a well-formed document, and writes it
 * to `build/reports/openapi-generated.json` — the file `scripts/openapi_coverage.py` reads. The
 * second is the annotation gate.
 *
 * Both read `/openapi-generated.json`; `/openapi` serves the identical document.
 */
class OpenApiGenerationTest {
    private companion object {
        /** Operation keys in a path item; everything else there is metadata, not an endpoint. */
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
    }

    @Test
    fun `generated spec is served and is well-formed`() =
        testApp { client ->
            TestData.setApiDocsEnabled(true)
            try {
                val response = client.get("/openapi-generated.json")
                assertEquals(HttpStatusCode.OK, response.status)

                val body = response.bodyAsText()
                File("build/reports").mkdirs()
                File("build/reports/openapi-generated.json").writeText(body)

                val doc = Json.parseToJsonElement(body).jsonObject
                val generatedPaths = doc["paths"]?.jsonObject?.keys.orEmpty()

                assertTrue(generatedPaths.isNotEmpty(), "generator produced no paths at all")
                assertTrue(
                    generatedPaths.any { it.startsWith("/api/") },
                    "generator produced no /api/ paths: ${generatedPaths.take(10)}",
                )
            } finally {
                TestData.setApiDocsEnabled(false)
            }
        }

    /**
     * Standing gate: every `/api` operation must carry a tag and a summary, which the Ktor
     * compiler plugin takes from the KDoc block above its handler. A new route without that
     * block reaches the spec as a bare path and fails here.
     *
     * Scoped to `/api` on purpose. The remaining routes are infrastructure — `/health`, `/`,
     * `/openapi` — or `staticResources` mounts like `/manual/{**}` and `/admin/{**}`, which
     * have no handler lambda for KDoc to sit above and so can never satisfy the gate.
     *
     * See docs/superpowers/plans/2026-08-11-route-kdoc-openapi.md for the annotation syntax.
     */
    @Test
    fun `every generated api endpoint carries a tag and a summary`() =
        testApp { client ->
            TestData.setApiDocsEnabled(true)
            val doc =
                try {
                    Json.parseToJsonElement(client.get("/openapi-generated.json").bodyAsText()).jsonObject
                } finally {
                    TestData.setApiDocsEnabled(false)
                }
            val unannotated =
                doc["paths"]!!
                    .jsonObject.entries
                    .filter { (path, _) -> path.startsWith("/api") }
                    .flatMap { (path, item) ->
                        item.jsonObject.entries
                            .filter { (method, _) -> method in HTTP_METHODS }
                            .filter { (_, op) ->
                                val o = op.jsonObject
                                o["tags"] == null || o["summary"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                            }.map { (method, _) -> "${method.uppercase()} $path" }
                    }.sorted()
            assertTrue(
                unannotated.isEmpty(),
                "Routes missing OpenAPI KDoc (${unannotated.size}): $unannotated",
            )
        }
}
