package org.example.project.core.auth

/**
 * Who is signed in on this till, as [PermissionManager.signedInPerson] reports it.
 *
 * The identity is the employee id from the access token's `sub` claim, which every token carries.
 * It deliberately is not the employee number from local storage: that can be absent, and two
 * people without one would compare equal, so a direct switch from one to the other went unnoticed.
 */
sealed interface SignedInPerson {
    /** Nobody is signed in: before the first login, and after every sign-out. */
    data object Nobody : SignedInPerson

    /** The person whose access token is loaded. */
    data class Employee(
        val employeeId: String,
    ) : SignedInPerson
}
