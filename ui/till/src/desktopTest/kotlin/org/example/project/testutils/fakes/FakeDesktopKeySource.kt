package org.example.project.testutils.fakes

import org.example.project.core.data.database.secret.DesktopKeySource
import org.example.project.core.data.database.secret.DesktopMasterKey
import org.example.project.core.data.database.secret.KeySlotProblemReporter
import org.example.project.core.data.database.secret.LoadedKeys
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DesktopKeySource] that runs the real [DesktopMasterKey] over two in-memory slots, so cipher tests exercise the real
 * load and create rules. [primary] is null for a machine with no keychain slot. [delayMillis] widens the window in
 * which concurrent first calls could race. [failure], when set, is thrown by every call in place of the slots.
 */
class FakeDesktopKeySource(
    val primary: FakeKeySlot? = FakeKeySlot(),
    val fallback: FakeKeySlot = FakeKeySlot(),
) : DesktopKeySource {
    @Volatile var delayMillis = 0L

    @Volatile var failure: Throwable? = null
    private val problems = KeySlotProblemReporter()
    private val loadCount = AtomicInteger()
    private val loadOrCreateCount = AtomicInteger()

    val loads: Int get() = loadCount.get()
    val loadOrCreates: Int get() = loadOrCreateCount.get()

    override fun load(): LoadedKeys {
        loadCount.incrementAndGet()
        pause()
        failure?.let { throw it }
        return DesktopMasterKey.load(primary, fallback, problems)
    }

    override fun loadOrCreate(): LoadedKeys {
        loadOrCreateCount.incrementAndGet()
        pause()
        failure?.let { throw it }
        return DesktopMasterKey.loadOrCreate(primary, fallback, problems)
    }

    private fun pause() {
        if (delayMillis > 0) Thread.sleep(delayMillis)
    }
}
