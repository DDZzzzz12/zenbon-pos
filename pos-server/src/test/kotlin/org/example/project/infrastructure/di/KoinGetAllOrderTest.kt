package org.example.project.infrastructure.di

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.assertEquals

/**
 * What `getAll` actually returns on the Koin this repo runs (4.2.2), recorded rather than assumed
 * (spec §15 C(d)).
 *
 * The multi-binding ports (`PostRestoreHook`, `ApprovalDetailsResolver`, `StaffRosterListener`,
 * `ConfigurationContributor`) are collected with `getAll`, and two consumers used to document that
 * they run their bindings "in order". Koin 4.2.2 keeps definitions in a `ConcurrentHashMap` keyed by
 * `type:qualifier:scope` and `getAll` filters `values` — the definition-order sort is commented out
 * in `InstanceRegistry.getAll`. So the order is the map's iteration order: a function of the keys,
 * not of the order the definitions were declared in. This test pins that: three qualified bindings
 * declared in two opposite orders come back in the same sequence.
 *
 * Every port has one binding today, so nothing depends on this yet. A second binding to a port whose
 * consumer cares about order needs an explicit order key on the port, not declaration order.
 */
@DisplayName("Koin getAll order is not declaration order")
class KoinGetAllOrderTest {
    private fun interface Named {
        fun label(): String
    }

    private fun getAllLabels(declarationOrder: List<String>): List<String> {
        val app =
            koinApplication {
                modules(
                    module {
                        declarationOrder.forEach { label -> single<Named>(named(label)) { Named { label } } }
                    },
                )
            }
        return try {
            app.koin.getAll<Named>().map { it.label() }
        } finally {
            app.close()
        }
    }

    @Test
    fun `getAll returns the same sequence whatever the declaration order`() {
        val forwards = getAllLabels(listOf("first", "second", "third"))
        val backwards = getAllLabels(listOf("third", "second", "first"))

        assertEquals(setOf("first", "second", "third"), forwards.toSet(), "every binding is returned")
        assertEquals(forwards, backwards, "getAll order followed the declaration order after all — re-read InstanceRegistry.getAll")
        assertEquals(OBSERVED_ORDER, forwards, "Koin's getAll iteration order changed — re-record it and re-check the KDocs")
    }

    private companion object {
        /** What Koin 4.2.2 returned on 2026-10-27; neither the declaration order nor its reverse. */
        val OBSERVED_ORDER = listOf("third", "first", "second")
    }
}
