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
 * Pins the set of `/api/v1/config` endpoints the server registers, independent of which route
 * function (or module) mounts them. Splitting `ConfigurationRoutes` by owning area must leave it
 * unchanged.
 */
class ConfigRoutesRegistrationSnapshotTest {
    private val expected =
        listOf(
            "GET /api/v1/config/{shopId}/billing-rules",
            "GET /api/v1/config/{shopId}/break-rules",
            "GET /api/v1/config/{shopId}/browser-stations",
            "GET /api/v1/config/{shopId}/cash-book-transfer-target",
            "GET /api/v1/config/{shopId}/datev-account-mapping",
            "GET /api/v1/config/{shopId}/datev-xml",
            "GET /api/v1/config/{shopId}/document-storage",
            "GET /api/v1/config/{shopId}/kassen-meldung",
            "GET /api/v1/config/{shopId}/kassen-meldung/pdf",
            "GET /api/v1/config/{shopId}/kassenbuch-beleg-age",
            "GET /api/v1/config/{shopId}/order-rules",
            "GET /api/v1/config/{shopId}/print-settings",
            "GET /api/v1/config/{shopId}/vat-mapping",
            "GET /api/v1/config/{shopId}/voucher-config",
            "GET /api/v1/config/{shopId}/web-access",
            "PUT /api/v1/config/{shopId}/billing-rules",
            "PUT /api/v1/config/{shopId}/break-rules",
            "PUT /api/v1/config/{shopId}/browser-stations",
            "PUT /api/v1/config/{shopId}/cash-book-transfer-target",
            "PUT /api/v1/config/{shopId}/datev-account-mapping",
            "PUT /api/v1/config/{shopId}/datev-xml",
            "PUT /api/v1/config/{shopId}/document-storage",
            "PUT /api/v1/config/{shopId}/kassenbuch-beleg-age",
            "PUT /api/v1/config/{shopId}/order-rules",
            "PUT /api/v1/config/{shopId}/print-settings",
            "PUT /api/v1/config/{shopId}/vat-mapping",
            "PUT /api/v1/config/{shopId}/voucher-config",
            "PUT /api/v1/config/{shopId}/web-access",
        )

    @Test
    fun test_config_routes_registered_unchanged() {
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
            actual = root.configEndpoints()
            TestDatabase.teardown()
        }
        assertEquals(expected, actual)
    }
}

private fun RoutingNode.configEndpoints(): List<String> {
    val result = mutableSetOf<String>()

    fun walk(node: RoutingNode) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) {
            val path = node.parent?.pathText().orEmpty()
            if (path.startsWith("/api/v1/config")) result += "${selector.method.value} $path"
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
