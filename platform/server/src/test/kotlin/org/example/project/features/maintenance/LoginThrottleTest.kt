package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.service.LoginThrottle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginThrottleTest {
    private var now = 1_000_000L
    private val throttle = LoginThrottle(nowMillis = { now })

    private fun fail(times: Int) = repeat(times) { throttle.recordFailure() }

    @Test
    fun `four consecutive failures are free`() {
        fail(4)
        assertNull(throttle.retryAfterSeconds())
    }

    @Test
    fun `the fifth failure blocks for thirty seconds`() {
        fail(5)
        assertEquals(30L, throttle.retryAfterSeconds())
    }

    @Test
    fun `the block lifts when the delay has passed`() {
        fail(5)
        now += 30_000
        assertNull(throttle.retryAfterSeconds())
    }

    @Test
    fun `the remaining time is rounded up so Retry-After is never zero while blocked`() {
        fail(5)
        now += 29_001
        assertEquals(1L, throttle.retryAfterSeconds())
    }

    @Test
    fun `each further failure doubles the delay`() {
        fail(5)
        now += 30_000
        throttle.recordFailure()
        assertEquals(60L, throttle.retryAfterSeconds())
        now += 60_000
        throttle.recordFailure()
        assertEquals(120L, throttle.retryAfterSeconds())
    }

    @Test
    fun `the delay stops growing at fifteen minutes`() {
        repeat(40) {
            throttle.recordFailure()
            now += 2_000_000
        }
        throttle.recordFailure()
        assertEquals(900L, throttle.retryAfterSeconds())
    }

    @Test
    fun `a success forgets every failure`() {
        fail(4)
        throttle.recordSuccess()
        fail(4)
        assertNull(throttle.retryAfterSeconds())
    }

    @Test
    fun `recordFailure reports the consecutive count`() {
        assertEquals(1, throttle.recordFailure())
        assertEquals(2, throttle.recordFailure())
    }
}
