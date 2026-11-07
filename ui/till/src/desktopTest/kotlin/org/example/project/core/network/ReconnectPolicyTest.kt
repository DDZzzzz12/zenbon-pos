package org.example.project.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {
    @Test
    fun `delays grow exponentially and cap, then exhaust`() {
        val p = ReconnectPolicy(maxAttempts = 3, baseDelayMs = 1000, maxDelayMs = 30000)
        assertEquals(1000, p.nextDelay())
        assertEquals(2000, p.nextDelay())
        assertEquals(4000, p.nextDelay())
        // Never giving up is the new contract: this used to assert null. Once the fast ladder is
        // spent the policy hands out the slow interval forever, so a station that lost the network
        // for minutes reconnects by itself instead of waiting for someone to restart the app.
        assertEquals(15_000L, p.nextDelay())
    }

    @Test
    fun `reset restarts the sequence`() {
        val p = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 500, maxDelayMs = 30000)
        p.nextDelay()
        p.reset()
        assertEquals(500, p.nextDelay())
    }

    @Test
    fun `after the fast attempts are exhausted the policy keeps retrying slowly`() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.nextDelay() }
        assertEquals(15_000L, policy.nextDelay(), "6th attempt falls back to the slow interval")
        assertEquals(15_000L, policy.nextDelay(), "and stays there")
        assertTrue(policy.isExhausted, "the UI still learns the fast attempts failed")
        policy.reset()
        assertEquals(1_000L, policy.nextDelay(), "reset returns to the fast ladder")
        assertFalse(policy.isExhausted)
    }
}
