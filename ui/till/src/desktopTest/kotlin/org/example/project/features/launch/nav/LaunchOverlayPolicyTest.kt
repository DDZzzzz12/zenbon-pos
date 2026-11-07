package org.example.project.features.launch.nav

import org.example.project.features.launch.presentation.nav.AppDestination
import org.example.project.features.launch.presentation.nav.clearsOverlaysOnLaunchTransition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchOverlayPolicyTest {
    @Test
    fun movingBetweenTwoDifferentLaunchScreensClears() {
        assertTrue(clearsOverlaysOnLaunchTransition(AppDestination.Offline(canChangeServerAddress = true), AppDestination.Resolving))
        assertTrue(clearsOverlaysOnLaunchTransition(AppDestination.Resolving, AppDestination.Setup(canChangeServerAddress = false)))
        assertTrue(
            clearsOverlaysOnLaunchTransition(
                AppDestination.Setup(canChangeServerAddress = true),
                AppDestination.Offline(canChangeServerAddress = true),
            ),
        )
        assertTrue(clearsOverlaysOnLaunchTransition(AppDestination.Resolving, AppDestination.Pairing))
    }

    @Test
    fun stayingOnTheSameLaunchScreenDoesNotClear() {
        assertFalse(
            clearsOverlaysOnLaunchTransition(
                AppDestination.Offline(canChangeServerAddress = true),
                AppDestination.Offline(canChangeServerAddress = false),
            ),
        )
        assertFalse(clearsOverlaysOnLaunchTransition(null, AppDestination.Offline(canChangeServerAddress = true)))
    }

    @Test
    fun aStationRefusalOnTheWayIntoPairingSurvives() {
        // LaunchCoordinator.refuse sends its warning after the cold reset, on exactly this move.
        assertFalse(clearsOverlaysOnLaunchTransition(AppDestination.StationSession, AppDestination.Pairing))
    }

    @Test
    fun enteringOrLeavingTheSignedInAppDoesNotClear() {
        assertFalse(clearsOverlaysOnLaunchTransition(AppDestination.Resolving, AppDestination.Login))
        assertFalse(clearsOverlaysOnLaunchTransition(AppDestination.Pairing, AppDestination.Main))
        assertFalse(clearsOverlaysOnLaunchTransition(AppDestination.Main, AppDestination.Pairing))
        assertFalse(clearsOverlaysOnLaunchTransition(AppDestination.Login, AppDestination.Offline(canChangeServerAddress = false)))
    }
}
