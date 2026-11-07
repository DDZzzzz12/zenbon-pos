package org.example.project.core.utils

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher for blocking local storage, above all the till's sealed secrets. On desktop a keychain that does not
 * answer costs up to 5 s per call, and Android Keystore calls go over IPC, so no secret read or write may run on the
 * UI thread.
 *
 * `Dispatchers.IO` on Android and the JVM. wasm has no IO dispatcher and a single thread, so it is
 * `Dispatchers.Default` there.
 *
 * In `shared` because the HTTP client reads and writes the token store too (`createPosHttpClient`). Classes take it
 * as a parameter that defaults to this, so a test can pass its own dispatcher.
 */
expect fun platformIoDispatcher(): CoroutineDispatcher
