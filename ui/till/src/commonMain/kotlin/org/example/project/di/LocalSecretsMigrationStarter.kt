package org.example.project.di

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.example.project.core.data.database.secret.LocalSecretsMigration
import org.example.project.core.utils.platformIoDispatcher
import org.koin.core.Koin

/**
 * Starts [LocalSecretsMigration] in the background: seals the secrets an older build left in plaintext and wipes them
 * from the database file. The Android application and the desktop `main` call it once, right after `initKoin`.
 *
 * The migration is resolved inside the coroutine, on the IO dispatcher, because building it opens the database and the
 * callers run on the main thread. A failure to resolve or run it is logged, never thrown: start-up must not fail over
 * it. The wasm build registers no migration — its store is not sealed — and does not call this.
 */
fun Koin.launchLocalSecretsMigration(): Job =
    CoroutineScope(SupervisorJob() + platformIoDispatcher()).launch {
        try {
            get<LocalSecretsMigration>().run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.withTag("LocalSecretsMigration").e(e) { "Could not run the local secrets migration" }
        }
    }
