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
import org.example.project.testutils.Snapshots
import org.example.project.testutils.TestDatabase
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin

/**
 * Pins every endpoint the server registers as sorted `METHOD path` lines, independent of which route
 * function or module mounts it. It generalises [ConfigRoutesRegistrationSnapshotTest] from
 * `/api/v1/config` to the whole routing tree: moving route files into `:platform-server` must leave
 * this list unchanged. The snapshot is `routes.snapshot.txt`; see [Snapshots] for re-recording,
 * which the platform extraction never does.
 */
class AllRoutesRegistrationSnapshotTest {
    @Test
    fun `every registered route is unchanged`() {
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
            actual = endpoints(root)
            TestDatabase.teardown()
        }
        Snapshots.assertMatches("routes.snapshot.txt", actual)
    }

    private fun endpoints(root: RoutingNode): List<String> {
        val result = mutableSetOf<String>()

        fun walk(node: RoutingNode) {
            val selector = node.selector
            if (selector is HttpMethodRouteSelector) result += "${selector.method.value} ${pathText(node.parent)}"
            node.children.forEach(::walk)
        }
        walk(root)
        return result.sorted()
    }

    /** The node's path with the non-path selectors (`(authenticate …)`, `<slash>`) left out. */
    private fun pathText(node: RoutingNode?): String =
        "/" +
            generateSequence(node) { it.parent }
                .mapNotNull { current ->
                    val text = current.selector.toString()
                    when {
                        text.isBlank() || text.startsWith("(") || text == "<slash>" -> null
                        else -> text.trim('/')
                    }
                }.filter { it.isNotBlank() }
                .toList()
                .asReversed()
                .joinToString("/")
}
