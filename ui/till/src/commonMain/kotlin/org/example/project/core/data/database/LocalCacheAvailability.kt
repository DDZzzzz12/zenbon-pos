package org.example.project.core.data.database

/**
 * Whether this client keeps a local SQLDelight cache of the server's data.
 *
 * True on Android and desktop, which build a `PosDatabase` and read through the `Local*` data
 * providers. False in the wasm browser preview, which builds no database and fetches from
 * pos-server on every read.
 *
 * Exists so the UI can hide controls that would otherwise do nothing — a "sync product data"
 * action on a client with no cache to sync into reports success while changing nothing, which is
 * the kind of quietly meaningless affordance an operator learns to distrust.
 *
 * A wrapper type rather than a bare `Boolean` because Koin resolves by type, and a raw `Boolean`
 * binding would collide with any other in the graph.
 */
data class LocalCacheAvailability(
    val isAvailable: Boolean,
)
