package org.example.project.infrastructure.security

import io.ktor.http.HttpMethod
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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import kotlin.test.assertTrue

/**
 * Guard test: every `/api` route Ktor actually serves must have a matching rule in
 * [RoutePermissions].
 *
 * [org.example.project.infrastructure.plugins.RouteAuthorizationPlugin] runs in the
 * `onCall` phase — before routing resolves — and denies any `/api` path with no
 * registry rule with **403 Forbidden**. A route added without a registry entry is
 * therefore completely unreachable in production, and the 403 is indistinguishable
 * from a 404, which makes the failure very hard to attribute. This test converts
 * that class of bug into a build failure.
 */
@DisplayName("RoutePermissions covers every registered Ktor route")
class RoutePermissionsCoverageTest {
    @Test
    fun `every mounted api route has a permission rule`() {
        val missing = mutableListOf<String>()
        val discovered = mutableListOf<String>()

        var app: io.ktor.server.application.Application? = null

        testApplication {
            application {
                TestDatabase.setup()
                startKoin {
                    modules(serializationModule, httpClientModule, reposModule, servicesModule)
                }
                module(enabled = false)
                app = this
            }

            startApplication()

            val root = app!!.plugin(RoutingRoot) as RoutingNode
            for ((path, method) in root.collectApiEndpoints()) {
                discovered += "${method.value} $path"
                if (RoutePermissions.findRule(path, method) == null) {
                    missing += "${method.value} $path"
                }
            }

            TestDatabase.teardown()
        }

        assertTrue(
            discovered.isNotEmpty(),
            "Route enumeration found no /api endpoints — the walker is broken, not the registry.",
        )
        assertTrue(
            missing.isEmpty(),
            buildString {
                appendLine("${missing.size} mounted /api route(s) have no RoutePermissions rule.")
                appendLine("They are unreachable in production: RouteAuthorizationPlugin denies them with 403.")
                appendLine("Add a rule for each in RoutePermissions.kt:")
                missing.sorted().forEach { appendLine("  $it") }
            },
        )
    }
}

/**
 * Walks the Ktor routing tree and returns every `(pathTemplate, method)` pair under `/api`.
 *
 * The returned path is Ktor's own template (e.g. `/api/employees/{id}`). Feeding a
 * template back into [RoutePermissions.findRule] is sound because a `{param}` segment in a
 * registry pattern matches any single segment, so a template segment matches iff a real
 * request segment would.
 */
private fun RoutingNode.collectApiEndpoints(): List<Pair<String, HttpMethod>> {
    val result = mutableListOf<Pair<String, HttpMethod>>()

    fun walk(node: RoutingNode) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) {
            val path = node.parent?.normalizedPath().orEmpty()
            if (path.startsWith("/api")) {
                result += path to selector.method
            }
        }
        node.children.forEach(::walk)
    }

    walk(this)
    return result.distinct()
}

/** Renders a routing node's full path, dropping non-path selectors (auth, method, etc.). */
private fun RoutingNode.normalizedPath(): String {
    val segments =
        generateSequence(this) { it.parent }
            .mapNotNull { node ->
                val text = node.selector.toString()
                when {
                    // Non-path selectors render as "(authenticate ...)", "(method:GET)", "(header ...)".
                    text.isBlank() || text.startsWith("(") -> null
                    // IgnoreTrailingSlash inserts a TrailingSlashRouteSelector that renders as
                    // "<slash>"; it matches the same path, so it contributes no segment.
                    text == "<slash>" -> null
                    else -> text.trim('/')
                }
            }.filter { it.isNotBlank() }
            .toList()
            .asReversed()
    return "/" + segments.joinToString("/")
}
