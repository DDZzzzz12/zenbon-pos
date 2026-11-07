package org.example.project.service

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.features.auth.data.repository.RefreshTokenRepositoryImpl
import org.example.project.features.auth.data.service.RefreshTokenServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.TimeProvider
import org.example.project.utils.formatter.registerTimeProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class RefreshTokenServiceTest {
    private lateinit var service: RefreshTokenServiceImpl
    private lateinit var employeeId: String

    /** How far the server clock (currentTimeStamp) is moved ahead of the real one. */
    private var clockOffset: Duration = Duration.ZERO

    private val shiftedClock =
        object : TimeProvider {
            override fun now(): LocalDateTime = (Clock.System.now() + clockOffset).toLocalDateTime(TimeZone.currentSystemDefault())

            override fun getTimeZone(): TimeZone = TimeZone.currentSystemDefault()
        }

    @BeforeEach
    fun setup() {
        // TestDatabase.setup() -> initDB() calls TimezoneConfig.registerAsTimeProvider(), which would
        // silently clobber a provider registered before it. Register ours AFTER the DB is up.
        TestDatabase.setup()
        registerTimeProvider(shiftedClock)
        service = RefreshTokenServiceImpl(RefreshTokenRepositoryImpl()) { 0L }
        employeeId = TestData.createAdmin().id.toString()
    }

    @AfterEach
    fun tearDown() {
        registerTimeProvider(TimeProvider.SYSTEM_DEFAULT)
        TestDatabase.teardown()
    }

    @Test
    fun `recorded token is refreshable`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("jti-1", "raw-1", employeeId, "dev-A", now, now.plus(30.days))
            service.assertRefreshable("jti-1", "raw-1", "dev-A", employeeId)
        }

    @Test
    fun `unknown jti is rejected`() =
        runBlocking {
            assertFailsWith<PosException> { service.assertRefreshable("nope", "raw", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `wrong hash is rejected`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("jti-2", "raw-2", employeeId, "dev-A", now, now.plus(30.days))
            assertFailsWith<PosException> { service.assertRefreshable("jti-2", "WRONG", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `device mismatch is rejected`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("jti-3", "raw-3", employeeId, "dev-A", now, now.plus(30.days))
            assertFailsWith<PosException> { service.assertRefreshable("jti-3", "raw-3", "dev-B", employeeId) }
            Unit
        }

    @Test
    fun `rotation issues a working new token`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))
            // Do NOT present "old" here — presenting a rotated-away token is itself a reuse event
            // (covered by the reuse test). Just confirm the rotated-in token works.
            service.assertRefreshable("new", "raw-new", "dev-A", employeeId)
        }

    @Test
    fun `reuse of rotated token revokes the family`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))
            clockOffset = 11.seconds
            assertFailsWith<PosException> { service.assertRefreshable("old", "raw-old", "dev-A", employeeId) }
            assertFailsWith<PosException> { service.assertRefreshable("new", "raw-new", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `revokeAllForEmployee kills active tokens`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("a", "raw-a", employeeId, "dev-A", now, now.plus(30.days))
            service.revokeAllForEmployee(employeeId)
            assertFailsWith<PosException> { service.assertRefreshable("a", "raw-a", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `pre-cutoff token is rejected`() =
        runBlocking {
            val now = Clock.System.now()
            val strict = RefreshTokenServiceImpl(RefreshTokenRepositoryImpl()) { now.plus(1.days).epochSeconds }
            strict.recordIssued("c", "raw-c", employeeId, "dev-A", now, now.plus(30.days))
            assertFailsWith<PosException> { strict.assertRefreshable("c", "raw-c", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `a just-rotated token is accepted again inside the grace window without revoking the family`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))

            service.assertRefreshable("old", "raw-old", "dev-A", employeeId) // second tab, same cookie
            service.assertRefreshable("new", "raw-new", "dev-A", employeeId) // first tab's token survives
        }

    @Test
    fun `the grace window is only for the same device`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))

            assertFailsWith<PosException> { service.assertRefreshable("old", "raw-old", "dev-B", employeeId) }
            Unit
        }

    @Test
    fun `replaying inside the window does not extend it`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))

            clockOffset = 8.seconds
            service.assertRefreshable("old", "raw-old", "dev-A", employeeId)
            service.rotate("old", "new-2", "raw-new-2", employeeId, "dev-A", now, now.plus(30.days)) // what the route does next

            clockOffset = 11.seconds // 11 s after the FIRST rotation, 3 s after the replay
            assertFailsWith<PosException> { service.assertRefreshable("old", "raw-old", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `a revoke-all ends the grace window for a just-rotated token`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))
            service.revokeAllForEmployee(employeeId) // logout everywhere / password change

            assertFailsWith<PosException> { service.assertRefreshable("old", "raw-old", "dev-A", employeeId) }
            assertFailsWith<PosException> { service.assertRefreshable("new", "raw-new", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `the grace window follows a live rotation chain`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "a", "raw-a", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("a", "b", "raw-b", employeeId, "dev-A", now, now.plus(30.days))

            // Tab 2 replays "old" while tab 1 has already rotated twice; the chain still ends in live "b".
            service.assertRefreshable("old", "raw-old", "dev-A", employeeId)
            service.assertRefreshable("b", "raw-b", "dev-A", employeeId)
        }

    @Test
    fun `a clock that moved back past the rotation gets no grace`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("old", "raw-old", employeeId, "dev-A", now, now.plus(30.days))
            service.rotate("old", "new", "raw-new", employeeId, "dev-A", now, now.plus(30.days))

            clockOffset = (-30).seconds // DST fall-back / westward shop-zone change: now is before revokedAt
            assertFailsWith<PosException> { service.assertRefreshable("old", "raw-old", "dev-A", employeeId) }
            Unit
        }

    @Test
    fun `a token revoked by logout gets no grace`() =
        runBlocking {
            val now = Clock.System.now()
            service.recordIssued("gone", "raw-gone", employeeId, "dev-A", now, now.plus(30.days))
            service.revokeByJti("gone")

            assertFailsWith<PosException> { service.assertRefreshable("gone", "raw-gone", "dev-A", employeeId) }
            Unit
        }
}
