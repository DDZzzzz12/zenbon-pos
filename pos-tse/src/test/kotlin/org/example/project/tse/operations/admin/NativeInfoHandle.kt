package org.example.project.tse.operations.admin

import com.secureflashcard.wormapi.WormInformation
import com.secureflashcard.wormapi.WormStore
import io.mockk.every
import io.mockk.mockk
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.tse.models.TseStatus

/**
 * A [WormInformation] that behaves like the real SWIG/JNI one with respect to lifetime.
 *
 * On the real SDK, `close()` frees the native handle, and any later getter reads freed native
 * memory — a SIGSEGV in `libWormAPIJni.so` that kills the whole server JVM (seen live on
 * `GET /api/tse/export/size`). The virtual WormAPI's `close()` is a no-op, so the suite never
 * noticed. This double fails loudly instead: every getter after `close()` throws.
 */
class NativeInfoHandle(configure: WormInformation.() -> Unit) {

    var closeCount: Int = 0
        private set

    var accessAfterClose: Int = 0
        private set

    val info: WormInformation = mockk {
        every { close() } answers { closeCount++ }
        every { delete() } answers { closeCount++ }
        configure()
    }

    /** Wrap a getter's value so that reading it on a closed handle throws. */
    fun <T> live(value: T): T {
        if (closeCount > 0) {
            accessAfterClose++
            throw IllegalStateException("WormInformation read after close() — use-after-free on the real SDK")
        }
        return value
    }

    companion object {
        /** A connected NORMAL-mode [TseStatus] that hands out [store], running locked work inline. */
        fun tseStatusFor(store: WormStore): TseStatus = mockk(relaxed = true) {
            every { isConnected() } returns true
            every { getMountPoint() } returns "/virtual/tse"
            every { getMode() } returns TseMode.NORMAL
            every { getWormStore() } returns store
            every { executeOnTseThread(any<() -> Any?>()) } answers { firstArg<() -> Any?>().invoke() }
        }
    }
}
