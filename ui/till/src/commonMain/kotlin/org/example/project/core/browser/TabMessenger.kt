package org.example.project.core.browser

/** A broadcast channel between the tabs of one browser origin. A tab never hears its own messages. */
interface TabMessenger {
    fun send(msg: String)

    fun onMessage(cb: (String) -> Unit)
}
