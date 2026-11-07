package org.example.project.core.data.database

import app.cash.sqldelight.db.SqlDriver

/**
 * There is no local database in the browser build.
 *
 * [createDriver] throws rather than returning a no-op driver on purpose: a driver that answered
 * every query with no rows would render an empty till that looks like a working one.
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver =
        throw UnsupportedOperationException(
            "The wasm build has no local database: `wasmModule` binds the ApiClient data providers " +
                "instead. Reaching this means a Local* provider was wired into the wasm graph.",
        )
}
