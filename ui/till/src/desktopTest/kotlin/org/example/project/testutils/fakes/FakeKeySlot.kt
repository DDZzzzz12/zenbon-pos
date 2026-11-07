package org.example.project.testutils.fakes

import org.example.project.core.data.database.secret.FallbackKeySlot
import org.example.project.core.data.database.secret.ReplaceableKeySlot
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory key slot, used as a keychain slot ([ReplaceableKeySlot]) and as a key file ([FallbackKeySlot]) alike. Set
 * [readError] to simulate a slot that cannot be read right now (a locked keychain), [failWrites] or [writeError] for one
 * that refuses writes. [writeAttempts] counts every call to [write] and [writeIfAbsent], [writes] only the ones that
 * stored a value.
 *
 * [writeIfAbsent] returns a value already held, whatever it is, without storing; judging whether it is a key is the
 * caller's job. [beforeWriteIfAbsent] runs inside it just before that check, e.g. to let another caller store a key first.
 */
class FakeKeySlot(
    @Volatile var value: String? = null,
    override val description: String = "fake slot",
) : ReplaceableKeySlot,
    FallbackKeySlot {
    @Volatile var readError: Throwable? = null

    @Volatile var writeError: Throwable? = null

    @Volatile var failWrites = false

    @Volatile var beforeWriteIfAbsent: () -> Unit = {}

    private val writeAttemptCount = AtomicInteger()
    private val writeCount = AtomicInteger()

    val writeAttempts: Int get() = writeAttemptCount.get()
    val writes: Int get() = writeCount.get()

    override fun read(): String? {
        readError?.let { throw it }
        return value
    }

    override fun write(value: String) {
        attemptWrite()
        store(value)
    }

    @Synchronized
    override fun writeIfAbsent(value: String): String {
        attemptWrite()
        beforeWriteIfAbsent()
        this.value?.let { return it }
        store(value)
        return value
    }

    private fun attemptWrite() {
        writeAttemptCount.incrementAndGet()
        writeError?.let { throw it }
        if (failWrites) error("fake slot refuses writes")
    }

    private fun store(value: String) {
        writeCount.incrementAndGet()
        this.value = value
    }
}
