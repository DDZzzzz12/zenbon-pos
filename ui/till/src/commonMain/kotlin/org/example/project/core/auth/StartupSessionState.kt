package org.example.project.core.auth

import io.ktor.client.HttpClient
import org.example.project.core.network.NotificationConnection
import org.example.project.core.network.forgetCachedBearer
import org.example.project.ui.auth.SessionState
import org.example.project.ui.auth.StartupPolicy

/**
 * The cashier terminal's app-level session: [StartupPolicy.WIPE], plus the one thing the wipe
 * cannot reach on its own — the bearer the HTTP client's Auth provider has cached.
 *
 * Ktor's bearer provider caches whatever `loadTokens` last returned and reloads it only after a 401,
 * so wiping [TokenStore] alone left the previous person's JWT riding on every request until the
 * next login cleared it. It happens in the field: after a crash, Android restarts
 * `PosNotificationService` alone in a new process, its connect loads the stored token into that
 * cache, and the activity's wipe then empties the store but not the cache (NEW-1, emulator pass 4).
 * Every request before the next login — including the notification stream's re-bind, whose explicit
 * `Authorization` header the Auth provider overwrites with its cached one — then went out as that
 * previous person.
 *
 * The same crash-restart left a second thing behind: the notification stream the revived service had
 * opened for that previous person. It stayed open, bound to them, with nobody logged in, until the next
 * login re-bound it — and a billing request addressed to them arrived on it and was shown to no one
 * (NOTE-8, emulator pass 5). The wipe therefore also ends a stream whose session it just ended; a
 * stream opened by a login after this point is untouched (see [NotificationConnection.closeIfSessionEnded]).
 *
 * Both platform `databaseModule`s build their [SessionState] here, so the wipe and the sweeps cannot
 * drift apart. The sweeps run after the wipe: a load in between reads the already-empty store.
 */
fun startupSessionState(
    tokenStore: TokenStore,
    httpClient: HttpClient,
    notificationConnection: NotificationConnection,
): SessionState =
    SessionState(tokenStore, StartupPolicy.WIPE).also {
        httpClient.forgetCachedBearer()
        notificationConnection.closeIfSessionEnded()
    }
