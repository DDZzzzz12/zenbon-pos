package org.example.project.features.maintenance

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.example.project.features.maintenance.data.service.LoginThrottle
import org.example.project.features.maintenance.data.service.MaintenanceAuthService
import org.example.project.features.maintenance.data.service.MaintenanceAuthService.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaintenanceAuthServiceTest {
    private val hash = BCrypt.withDefaults().hashToString(4, "right-password".toCharArray())
    private val config = MaintenanceConfig("maint", hash, 30)
    private var now = 0L
    private val throttle = LoginThrottle(nowMillis = { now })
    private val verifiedAgainst = mutableListOf<String>()

    private fun service(settings: MaintenanceConfig = config) =
        MaintenanceAuthService(
            throttle = throttle,
            config = { settings },
            verifyPassword = { password, candidateHash ->
                verifiedAgainst += candidateHash
                BCrypt.verifyer().verify(password.toCharArray(), candidateHash).verified
            },
            issueToken = { minutes -> "token-for-$minutes" },
        )

    @Test
    fun `the right credentials are granted a token that lasts the configured session`() =
        runBlocking<Unit> {
            val outcome = service().login("maint", "right-password")
            val granted = assertIs<Outcome.Granted>(outcome)
            assertEquals("token-for-30", granted.accessToken)
            assertEquals(1800L, granted.expiresInSeconds)
        }

    @Test
    fun `a wrong password is denied`() =
        runBlocking<Unit> {
            assertIs<Outcome.Denied>(service().login("maint", "wrong"))
        }

    @Test
    fun `a wrong username is denied after the SAME amount of hashing work`() =
        runBlocking<Unit> {
            assertIs<Outcome.Denied>(service().login("someone-else", "right-password"))
            assertEquals(1, verifiedAgainst.size, "BCrypt must still run, or the response time reveals the username")
            assertNotEquals(hash, verifiedAgainst.single(), "it runs against a dummy hash, never the real one")
        }

    @Test
    fun `the fifth failure throttles the next attempt even with the right password`() =
        runBlocking<Unit> {
            val auth = service()
            repeat(5) { auth.login("maint", "wrong") }
            val outcome = auth.login("maint", "right-password")
            assertEquals(30L, assertIs<Outcome.Throttled>(outcome).retryAfterSeconds)
        }

    @Test
    fun `a throttled attempt does no hashing at all`() =
        runBlocking<Unit> {
            val auth = service()
            repeat(5) { auth.login("maint", "wrong") }
            val before = verifiedAgainst.size
            auth.login("maint", "right-password")
            assertEquals(before, verifiedAgainst.size)
        }

    @Test
    fun `after the delay the right password works again and resets the counter`() =
        runBlocking<Unit> {
            val auth = service()
            repeat(5) { auth.login("maint", "wrong") }
            now += 30_000
            assertIs<Outcome.Granted>(auth.login("maint", "right-password"))
            repeat(4) { auth.login("maint", "wrong") }
            assertIs<Outcome.Granted>(auth.login("maint", "right-password"))
        }

    @Test
    fun `the dummy hash for a wrong username has the configured hash's cost`() =
        runBlocking<Unit> {
            assertIs<Outcome.Denied>(service().login("someone-else", "right-password"))
            val dummy = verifiedAgainst.single()
            assertTrue(dummy.startsWith("\$2a\$04\$"), "a different cost makes a wrong username measurably faster or slower: $dummy")
            assertNotEquals(hash, dummy)
        }

    @Test
    fun `a login while another is still verifying is refused without hashing`() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val auth =
                MaintenanceAuthService(
                    throttle = throttle,
                    config = { config },
                    verifyPassword = { password, candidateHash ->
                        calls.incrementAndGet()
                        entered.complete(Unit)
                        // Bounded, so the pre-fix code (which lets the second call hash too) fails
                        // the assertion below instead of hanging the suite.
                        release.await(5, TimeUnit.SECONDS)
                        BCrypt.verifyer().verify(password.toCharArray(), candidateHash).verified
                    },
                    issueToken = { minutes -> "token-for-$minutes" },
                )
            val first = async { auth.login("maint", "right-password") }
            entered.await()

            val second = auth.login("maint", "wrong")

            assertEquals(1L, assertIs<Outcome.Throttled>(second).retryAfterSeconds)
            assertEquals(1, calls.get(), "the refused attempt must not reach BCrypt")
            release.countDown()
            assertIs<Outcome.Granted>(first.await())
        }

    @Test
    fun `a password over 72 bytes is denied and counted, not an exception`() =
        runBlocking<Unit> {
            // The real verifier: favre's strict long-password strategy throws for > 72 bytes.
            val auth = MaintenanceAuthService(throttle = throttle, config = { config }, issueToken = { "t" })
            val tooLong = "x".repeat(100)
            repeat(5) { assertIs<Outcome.Denied>(auth.login("maint", tooLong)) }
            assertIs<Outcome.Throttled>(auth.login("maint", "right-password"), "every over-long attempt counts as a failure")
        }

    @Test
    fun `calling login while the feature is off is a programming error, not a denial`() {
        val off = service(MaintenanceConfig.DISABLED)
        assertFailsWith<IllegalStateException> { runBlocking { off.login("maint", "x") } }
    }
}
