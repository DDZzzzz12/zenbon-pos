package org.example.project.core.data.database.secret

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KeychainCallPolicyTest {
    @Test
    fun the_defaults_wait_five_seconds_and_back_off_from_thirty_seconds_to_thirty_minutes() {
        val policy = KeychainCallPolicy()

        assertEquals(5.seconds, policy.timeout)
        assertEquals(30.seconds, policy.backoff)
        assertEquals(30.minutes, policy.maxBackoff)
    }

    @Test
    fun a_timeout_that_is_not_positive_is_rejected() {
        assertFailsWith<IllegalArgumentException> { KeychainCallPolicy(timeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { KeychainCallPolicy(timeout = (-1).seconds) }

        assertEquals(1.seconds, KeychainCallPolicy(timeout = 1.seconds).timeout)
    }

    @Test
    fun a_backoff_that_is_not_positive_is_rejected() {
        assertFailsWith<IllegalArgumentException> { KeychainCallPolicy(backoff = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { KeychainCallPolicy(backoff = (-1).seconds) }

        assertEquals(1.seconds, KeychainCallPolicy(backoff = 1.seconds).backoff)
    }

    @Test
    fun a_max_backoff_shorter_than_the_backoff_is_rejected() {
        assertFailsWith<IllegalArgumentException> { KeychainCallPolicy(backoff = 1.minutes, maxBackoff = 59.seconds) }

        assertEquals(1.minutes, KeychainCallPolicy(backoff = 1.minutes, maxBackoff = 1.minutes).maxBackoff)
    }
}
