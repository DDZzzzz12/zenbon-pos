package org.example.project.routes

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import org.example.project.infrastructure.di.httpClientModule
import org.example.project.infrastructure.di.reposModule
import org.example.project.infrastructure.di.serializationModule
import org.example.project.infrastructure.di.servicesModule
import org.example.project.infrastructure.server.module
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import kotlin.test.assertEquals

/**
 * Pins the set of `/api/v1/kassenbuch` endpoints the server registers, independent of which route
 * function mounts them. Splitting the DATEV endpoints out of `KassenbuchRoutes` must leave it
 * unchanged.
 */
class KassenbuchRoutesRegistrationSnapshotTest {
    private val expected =
        listOf(
            "DELETE /api/v1/kassenbuch/entries/{headerId}/link-document",
            "GET /api/v1/kassenbuch/available-cash",
            "GET /api/v1/kassenbuch/balance",
            "GET /api/v1/kassenbuch/books",
            "GET /api/v1/kassenbuch/books/{bookId}/close/preview",
            "GET /api/v1/kassenbuch/books/{bookId}/closes",
            "GET /api/v1/kassenbuch/books/{bookId}/signing-terminal/candidates",
            "GET /api/v1/kassenbuch/cash-count/expected",
            "GET /api/v1/kassenbuch/entries",
            "GET /api/v1/kassenbuch/entries/grouped/{headerId}",
            "GET /api/v1/kassenbuch/entries/recent",
            "GET /api/v1/kassenbuch/entries/search",
            "GET /api/v1/kassenbuch/entries/{entryId}",
            "GET /api/v1/kassenbuch/export/buchungsstapel",
            "GET /api/v1/kassenbuch/export/datev-xml",
            "GET /api/v1/kassenbuch/export/datev-xml/statistics",
            "POST /api/v1/kassenbuch",
            "POST /api/v1/kassenbuch/books",
            "POST /api/v1/kassenbuch/books/{bookId}/close",
            "POST /api/v1/kassenbuch/cash-count",
            "POST /api/v1/kassenbuch/entries/{headerId}/correct",
            "POST /api/v1/kassenbuch/transfers",
            "POST /api/v1/kassenbuch/transfers/{transferId}/reverse",
            "PUT /api/v1/kassenbuch/books/{bookId}",
            "PUT /api/v1/kassenbuch/books/{bookId}/signing-terminal",
            "PUT /api/v1/kassenbuch/entries/{headerId}/link-document",
            "PUT /api/v1/kassenbuch/entries/{headerId}/replace-document",
        )

    @Test
    fun test_kassenbuch_routes_registered_unchanged() {
        var actual: List<String> = emptyList()
        var app: Application? = null
        testApplication {
            application {
                TestDatabase.setup()
                startKoin { modules(serializationModule, httpClientModule, reposModule, servicesModule) }
                module(enabled = false)
                app = this
            }
            startApplication()
            val root = app!!.plugin(RoutingRoot) as RoutingNode
            actual = root.kassenbuchEndpoints()
            TestDatabase.teardown()
        }
        assertEquals(expected, actual)
    }
}

private fun RoutingNode.kassenbuchEndpoints(): List<String> {
    val result = mutableSetOf<String>()

    fun walk(node: RoutingNode) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) {
            val path = node.parent?.pathText().orEmpty()
            if (path.startsWith("/api/v1/kassenbuch")) result += "${selector.method.value} $path"
        }
        node.children.forEach(::walk)
    }
    walk(this)
    return result.sorted()
}

private fun RoutingNode.pathText(): String =
    "/" +
        generateSequence(this) { it.parent }
            .mapNotNull { node ->
                val text = node.selector.toString()
                when {
                    text.isBlank() || text.startsWith("(") || text == "<slash>" -> null
                    else -> text.trim('/')
                }
            }.filter { it.isNotBlank() }
            .toList()
            .asReversed()
            .joinToString("/")
