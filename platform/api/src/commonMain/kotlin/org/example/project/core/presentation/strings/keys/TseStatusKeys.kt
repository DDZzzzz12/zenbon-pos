package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * String keys for the persistent TSE-status banner shown on the main screen
 * when the TSE is configured NORMAL but offline (bookings are queued for Nachsignieren).
 */
object TseStatusKeys {
    val offlineBannerTitle = StringKey("tse.status.offline_banner.title")
    val offlineBannerMessage = StringKey("tse.status.offline_banner.message")
}
