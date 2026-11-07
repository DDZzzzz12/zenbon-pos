package org.example.project.testutils.fakes

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Runs its tasks on [delegate] and marks the thread while one runs, so a test can tell work done on this dispatcher
 * from work done on the caller's. Pass it where production code takes its IO dispatcher, wrapping the test's own
 * `StandardTestDispatcher` so virtual time still drives everything.
 */
class IoMarkingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val running = ThreadLocal.withInitial { false }

    /** True while the calling code runs inside one of this dispatcher's tasks. */
    val isCurrent: Boolean
        get() = running.get()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(
            context,
            Runnable {
                val outer = running.get()
                running.set(true)
                try {
                    block.run()
                } finally {
                    running.set(outer)
                }
            },
        )
    }
}
