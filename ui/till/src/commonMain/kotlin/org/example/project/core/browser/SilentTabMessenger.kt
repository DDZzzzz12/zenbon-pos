package org.example.project.core.browser

/** A channel with no other tabs on it: sends vanish, nothing is ever heard. */
object SilentTabMessenger : TabMessenger {
    override fun send(msg: String) = Unit

    override fun onMessage(cb: (String) -> Unit) = Unit
}
