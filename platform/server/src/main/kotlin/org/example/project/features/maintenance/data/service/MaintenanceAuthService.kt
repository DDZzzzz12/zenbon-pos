package org.example.project.features.maintenance.data.service

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.example.project.core.security.JwtConfig
import org.example.project.features.maintenance.MaintenanceConfig
import org.example.project.features.maintenance.MaintenancePolicy
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks the maintainer's credentials and mints the session token.
 *
 * Timing: every attempt that reaches the credential check runs BCrypt once at the configured hash's
 * cost. A wrong username is verified against a dummy hash generated at that SAME cost, so the response
 * time does not reveal whether the username exists; usernames are compared by digest so the comparison
 * itself is constant-time. Three cases deliberately do no hashing: a throttled attempt and an attempt
 * refused because another is still being checked (refusing cheaply is the point of throttling), and a
 * password over BCrypt's 72-byte limit, which the verifier rejects before hashing and which is counted
 * as a failure.
 *
 * Concurrency: one attempt is checked at a time. The throttle's check and its failure count are
 * separate steps, so without this a burst of parallel requests would all pass the check before the
 * fifth failure was recorded — unlimited guesses, and unlimited BCrypt work on the shared dispatcher.
 * A request arriving while another is in flight is answered as throttled for one second instead of
 * queueing.
 *
 * Logging: outcomes are logged, the submitted username and password never are.
 */
class MaintenanceAuthService(
    private val throttle: LoginThrottle = LoginThrottle(),
    private val config: () -> MaintenanceConfig = MaintenancePolicy::current,
    private val verifyPassword: (password: String, hash: String) -> Boolean = { password, hash -> bcryptVerifies(password, hash) },
    private val issueToken: (lifetimeMinutes: Int) -> String = JwtConfig::generateMaintainerToken,
) {
    sealed interface Outcome {
        data class Granted(
            val accessToken: String,
            val expiresInSeconds: Long,
        ) : Outcome

        data object Denied : Outcome

        data class Throttled(
            val retryAfterSeconds: Long,
        ) : Outcome
    }

    /** Held from the throttle check through the recorded outcome, so the two cannot interleave. */
    private val inFlight = Mutex()

    suspend fun login(
        username: String,
        password: String,
    ): Outcome {
        val settings = config()
        check(settings.enabled) { "The maintainer account is not configured; the route must answer 404 before reaching here" }

        if (!inFlight.tryLock()) return Outcome.Throttled(BUSY_RETRY_AFTER_SECONDS)
        try {
            throttle.retryAfterSeconds()?.let { return Outcome.Throttled(it) }
            return checkCredentials(settings, username, password)
        } finally {
            inFlight.unlock()
        }
    }

    private suspend fun checkCredentials(
        settings: MaintenanceConfig,
        username: String,
        password: String,
    ): Outcome {
        val usernameMatches = sameText(username, settings.username)
        val passwordMatches =
            withContext(Dispatchers.Default) {
                // Resolved on both paths, so the one-off cost of generating it cannot single out either.
                val dummy = dummyHashAt(MaintenanceConfig.costOf(settings.passwordHash))
                verifyPassword(password, if (usernameMatches) settings.passwordHash else dummy)
            }

        if (usernameMatches && passwordMatches) {
            throttle.recordSuccess()
            logger.info("Maintainer login succeeded")
            return Outcome.Granted(issueToken(settings.sessionMinutes), settings.sessionMinutes * 60L)
        }
        val failures = throttle.recordFailure()
        logger.warn("Maintainer login failed (consecutive failures: {})", failures)
        return Outcome.Denied
    }

    private fun sameText(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(sha256(a), sha256(b))

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private companion object {
        private val logger = LoggerFactory.getLogger(MaintenanceAuthService::class.java)

        /** What a request is told while another attempt is being checked. */
        private const val BUSY_RETRY_AFTER_SECONDS = 1L

        /** One dummy hash per cost, so a wrong username costs exactly what a wrong password costs. */
        private val dummyHashes = ConcurrentHashMap<Int, String>()

        private fun dummyHashAt(cost: Int): String =
            dummyHashes.computeIfAbsent(cost) { BCrypt.withDefaults().hashToString(it, "not-the-maintainer-password".toCharArray()) }

        /**
         * The production verifier. favre's default strategy throws [IllegalArgumentException] for a
         * password over 72 bytes; that is a wrong password, not a malformed request, so it must be
         * counted by the throttle like any other failure.
         */
        private fun bcryptVerifies(
            password: String,
            hash: String,
        ): Boolean =
            try {
                BCrypt.verifyer().verify(password.toCharArray(), hash).verified
            } catch (e: IllegalArgumentException) {
                false
            }
    }
}
