package org.example.project.testutils.fakes

import org.example.project.core.auth.TokenStore

/**
 * Creates a [TokenStore] backed by an in-memory [FakeKeyValueStore].
 *
 * TokenStore is a concrete class, so tests use a real instance over controllable storage
 * rather than a hand-written double.
 */
object FakeTokenStore {
    /** A new store with empty in-memory storage. */
    fun create(): TokenStore = TokenStore(FakeKeyValueStore())

    /** A store sharing the caller's [store], for tests that assert on raw persisted keys. */
    fun create(store: FakeKeyValueStore): TokenStore = TokenStore(store)
}
