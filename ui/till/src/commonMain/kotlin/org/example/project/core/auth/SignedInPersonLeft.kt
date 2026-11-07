package org.example.project.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits the person who just left, each time a signed-in person signs out or another person signs
 * in without a sign-out in between.
 *
 * For the ViewModels `MainScreen` obtains from the root ViewModelStore: they outlive a sign-out, so
 * whatever they hold for one person must be dropped before the next one sees it. Nobody signing in
 * is not a departure, so state built up after a sign-in is never cleared by that sign-in landing
 * late, and a token refresh for the same person emits nothing.
 */
fun Flow<SignedInPerson>.personLeft(): Flow<SignedInPerson.Employee> =
    flow {
        var previous: SignedInPerson? = null
        collect { current ->
            val left = previous
            previous = current
            if (left is SignedInPerson.Employee && left != current) emit(left)
        }
    }
