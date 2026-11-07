package org.example.project.testutils.fakes

import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.database.LocalDatabaseManager

/**
 * Creates a PermissionManager backed by in-memory fakes.
 *
 * Since PermissionManager is a concrete class (not an interface),
 * this helper provides a real instance with controllable in-memory storage
 * suitable for unit tests.
 */
object FakePermissionManager {
    /**
     * Creates a new PermissionManager with empty in-memory backing stores.
     */
    fun create(): PermissionManager {
        val localDb = FakeLocalDatabaseManager.create()
        val tokenStore = FakeTokenStore.create()
        return PermissionManager(localDb, tokenStore)
    }

    /**
     * Creates a new PermissionManager using the provided dependencies.
     *
     * Use this when you need to share the same LocalDatabaseManager
     * and TokenStore across multiple components in a test.
     *
     * @param localDb The LocalDatabaseManager to use
     * @param tokenStore The TokenStore to use
     */
    fun create(
        localDb: LocalDatabaseManager,
        tokenStore: TokenStore,
    ): PermissionManager = PermissionManager(localDb, tokenStore)
}
