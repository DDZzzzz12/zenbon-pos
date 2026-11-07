package org.example.project.testutils.fakes

import org.example.project.core.data.database.secret.DesktopKeySource
import org.example.project.core.data.database.secret.LoadedKeys
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DesktopKeySource] that hands out prepared results in order, for interleavings the real key source cannot be steered
 * into: [load] returns the next of `loads`, [loadOrCreate] the next of `loadOrCreates`. A call with no result left throws
 * an [AssertionError]. [loads] counts every call to [load].
 */
class ScriptedDesktopKeySource(
    loads: List<LoadedKeys>,
    loadOrCreates: List<LoadedKeys> = emptyList(),
) : DesktopKeySource {
    private val pendingLoads = ConcurrentLinkedQueue(loads)
    private val pendingLoadOrCreates = ConcurrentLinkedQueue(loadOrCreates)
    private val loadCount = AtomicInteger()

    val loads: Int get() = loadCount.get()

    override fun load(): LoadedKeys {
        loadCount.incrementAndGet()
        return pendingLoads.poll() ?: throw AssertionError("No load() result prepared")
    }

    override fun loadOrCreate(): LoadedKeys = pendingLoadOrCreates.poll() ?: throw AssertionError("No loadOrCreate() result prepared")
}
