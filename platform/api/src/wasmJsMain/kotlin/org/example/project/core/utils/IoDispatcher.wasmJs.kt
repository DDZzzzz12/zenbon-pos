package org.example.project.core.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** wasm has no IO dispatcher and a single thread. */
actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
