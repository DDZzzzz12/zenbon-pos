package org.example.project.routes

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiDocsGateTest {
    private val docPaths =
        listOf(
            "/openapi",
            "/openapi-generated.json",
            "/swagger",
            "/swagger/openapi.json",
        )

    @Test
    fun `docs are 404 when switched off`() =
        testApp(setupData = { TestData.createShopInfo() }) { client ->
            TestData.setApiDocsEnabled(false)
            docPaths.forEach { assertEquals(HttpStatusCode.NotFound, client.get(it).status, it) }
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }

    @Test
    fun `switching on and off takes effect without a restart`() =
        testApp(setupData = { TestData.createShopInfo() }) { client ->
            try {
                TestData.setApiDocsEnabled(true)
                docPaths.forEach { assertEquals(HttpStatusCode.OK, client.get(it).status, it) }
                assertEquals(HttpStatusCode.OK, client.get("/health").status)
                TestData.setApiDocsEnabled(false)
                docPaths.forEach { assertEquals(HttpStatusCode.NotFound, client.get(it).status, it) }
                assertEquals(HttpStatusCode.OK, client.get("/health").status)
            } finally {
                TestData.setApiDocsEnabled(false)
            }
        }
}
