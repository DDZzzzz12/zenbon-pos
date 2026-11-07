package org.example.project.infrastructure.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.project.testutils.Snapshots
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the set of operations in `/openapi-generated.json` as sorted `METHOD path` lines (spec §15
 * C(f)). The Ktor compiler plugin derives the document from the route files it compiles, so a route
 * file moved into a module the plugin is not applied to drops out of the spec while the route itself
 * keeps working — nothing else notices. The snapshot is `openapi-paths.snapshot.txt`; see
 * [Snapshots] for re-recording, which the platform extraction never does.
 */
class OpenApiPathSnapshotTest {
    private companion object {
        /** Operation keys in a path item; everything else there is metadata, not an endpoint. */
        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")
    }

    @Test
    fun `generated spec lists the same operations`() =
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
            val operations =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["paths"]!!
                    .jsonObject.entries
                    .flatMap { (path, item) ->
                        item.jsonObject.keys
                            .filter { it in HTTP_METHODS }
                            .map { method -> "${method.uppercase()} $path" }
                    }.sorted()
            Snapshots.assertMatches("openapi-paths.snapshot.txt", operations)
        }
}
