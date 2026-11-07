package org.example.project.core.browser

/** In-memory stand-in for a BroadcastChannel: delivers synchronously to every other messenger. */
class FakeTabBus {
    private val members = mutableListOf<Member>()

    fun join(): TabMessenger = Member().also { members += it }

    private inner class Member : TabMessenger {
        var listener: (String) -> Unit = {}

        override fun send(msg: String) {
            members.filter { it !== this }.forEach { it.listener(msg) }
        }

        override fun onMessage(cb: (String) -> Unit) {
            listener = cb
        }
    }
}
