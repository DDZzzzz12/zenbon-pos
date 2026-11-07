package org.example.project.core.data.cache

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide signal that a cached entity changed. Cache writers emit; dashboard VMs collect
 * and re-read their one-shot snapshot. extraBufferCapacity keeps non-suspending tryEmit safe.
 */
class CacheChangeBus {
    private val _changes = MutableSharedFlow<CacheChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<CacheChange> = _changes.asSharedFlow()

    suspend fun emit(change: CacheChange) = _changes.emit(change)

    fun tryEmit(change: CacheChange) {
        _changes.tryEmit(change)
    }
}
