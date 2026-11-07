package org.example.project.core.notification

/**
 * Whether the Android "allow background operation" prompt is shown at this login.
 *
 * "Later" used to mean "ask again at the next login", which on a till that logs in several times a day
 * put the same dialog in front of the operator at every sign-in. Now it holds the question back for
 * [POSTPONE_MILLIS]; "set up now" and an already-exempt app never ask again.
 */
object BackgroundPromptPolicy {
    const val POSTPONE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    fun shouldPrompt(
        ignoringBatteryOptimizations: Boolean,
        alreadySentToSettings: Boolean,
        postponedUntilMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (ignoringBatteryOptimizations || alreadySentToSettings) return false
        return nowMillis >= postponedUntilMillis
    }
}
