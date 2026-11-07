package org.example.project.features.maintenance

import org.example.project.utils.config.AppConfig

/**
 * The maintainer account, as configured in `application.yaml` / the environment.
 *
 * The feature is on only when BOTH [username] and [passwordHash] are set. A hash that is present but
 * not a BCrypt string is a startup error instead of "off": a typo in the one variable that guards a
 * privileged account must not look like a deliberately disabled feature.
 */
data class MaintenanceConfig(
    val username: String,
    val passwordHash: String,
    val sessionMinutes: Int,
) {
    val enabled: Boolean get() = username.isNotBlank() && passwordHash.isNotBlank()

    /** Never prints the username or the hash: this object ends up in debug output. */
    override fun toString(): String = "MaintenanceConfig(enabled=$enabled, sessionMinutes=$sessionMinutes)"

    companion object {
        const val DEFAULT_SESSION_MINUTES = 30
        private const val MAX_SESSION_MINUTES = 480
        private const val MIN_BCRYPT_COST = 4
        private const val MAX_BCRYPT_COST = 31
        private val BCRYPT_HASH = Regex("^[$]2[aby][$][0-9]{2}[$][./A-Za-z0-9]{53}\$")

        val DISABLED = MaintenanceConfig(username = "", passwordHash = "", sessionMinutes = DEFAULT_SESSION_MINUTES)

        fun fromYaml(config: AppConfig): MaintenanceConfig =
            of(
                username = config.optional("maintenance.username"),
                passwordHash = config.optional("maintenance.passwordHash"),
                sessionMinutes = config.optional("maintenance.sessionMinutes"),
            )

        fun of(
            username: String?,
            passwordHash: String?,
            sessionMinutes: String?,
        ): MaintenanceConfig {
            val user = username.orEmpty().trim()
            val hash = passwordHash.orEmpty().trim()
            check(hash.isEmpty() || BCRYPT_HASH.matches(hash)) {
                "MAINTAINER_PASSWORD_HASH is not a BCrypt hash. Create one with: java -jar pos-server.jar --hash-maintainer-password"
            }
            if (hash.isNotEmpty()) requireUsableCost(hash)
            return MaintenanceConfig(user, hash, parseMinutes(sessionMinutes))
        }

        /** The cost of a hash that already matched [BCRYPT_HASH]: the two digits after `$2x$`. */
        fun costOf(hash: String): Int = hash.substring(4, 6).toInt()

        /** BCrypt defines costs 4 to 31; a hash outside that can never verify, so the account could never log in. */
        private fun requireUsableCost(hash: String) {
            val cost = costOf(hash)
            check(cost in MIN_BCRYPT_COST..MAX_BCRYPT_COST) {
                "MAINTAINER_PASSWORD_HASH has BCrypt cost $cost; it must be from $MIN_BCRYPT_COST to $MAX_BCRYPT_COST. " +
                    "Create one with: java -jar pos-server.jar --hash-maintainer-password"
            }
        }

        private fun parseMinutes(raw: String?): Int {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return DEFAULT_SESSION_MINUTES
            val minutes = text.toIntOrNull()
            check(minutes != null && minutes in 1..MAX_SESSION_MINUTES) {
                "MAINTAINER_SESSION_MINUTES must be a whole number from 1 to $MAX_SESSION_MINUTES, was '$text'"
            }
            return minutes
        }

        private fun AppConfig.optional(path: String): String? = if (hasPath(path)) getString(path) else null
    }
}
