package org.example.project.features.launch.presentation.nav

/**
 * Whether moving the launch flow from [previous] to [next] must drop the queued global overlays.
 *
 * True only for a move between two different launch screens: [AppDestination.Resolving],
 * [AppDestination.Offline], [AppDestination.Setup], [AppDestination.InsecureServerUrl] and [AppDestination.Pairing]. A toast raised on
 * one of them (a failed retry, a rejected server address) describes that screen; carried onto the
 * next one it reads as a fresh failure and, heading the one-at-a-time queue, holds back whatever
 * the new screen raises.
 *
 * Everything else keeps its overlays on purpose:
 * - StationSession → Pairing carries the refusal warning `LaunchCoordinator.refuse` sends right
 *   after the cold reset; clearing here would swallow it.
 * - StationSession → StationBlocked carries the blocked-station warning.
 * - Leaving Main is already covered by the sign-out clear in `AppNavigation`.
 * - Offline → Offline with a different `canChangeServerAddress` is the same screen.
 */
fun clearsOverlaysOnLaunchTransition(
    previous: AppDestination?,
    next: AppDestination,
): Boolean {
    val from = previous?.launchScreenKind() ?: return false
    val to = next.launchScreenKind() ?: return false
    return from != to
}

/** A stable number per launch screen, ignoring screen parameters; null for everything else. */
private fun AppDestination.launchScreenKind(): Int? =
    when (this) {
        AppDestination.Resolving -> 0
        is AppDestination.Offline -> 1
        is AppDestination.Setup -> 2
        AppDestination.Pairing -> 3
        AppDestination.InsecureServerUrl -> 4
        else -> null
    }
