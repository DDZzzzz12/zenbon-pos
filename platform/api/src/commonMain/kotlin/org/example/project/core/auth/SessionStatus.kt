package org.example.project.core.auth

/**
 * App-level authentication status.
 *
 * [CHECKING] means the session is being verified with the server before the app decides — today
 * that means adminApp resuming its session through the refresh cookie on page load.
 */
enum class SessionStatus { CHECKING, AUTHENTICATED, UNAUTHENTICATED }
