package org.example.project.core.data.database.secret

/**
 * The `user_preferences` keys whose values are sealed at rest.
 *
 * These spell keys owned elsewhere — TokenStore's (shared) and LocalDatabaseManager's — on purpose:
 * the protecting store must not depend on either class. `every_secret_the_app_actually_writes_lands_sealed`
 * fails if an owner renames a key.
 */
object SecretPreferenceKeys {
    val ALL: Set<String> =
        setOf(
            "device_token",
            "password", // "Remember me" password — LocalDatabaseManager.LOGIN_PASSWORD
            "jwt_access_token",
            "jwt_refresh_token",
        )
}
