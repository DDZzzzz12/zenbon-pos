package org.example.project.tse.virtual

import com.secureflashcard.wormapi.WormError
import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * TSE 2.0 permits exactly one logged-in user. Logging in a second evicts the first —
 * the change that breaks "log in Admin once and rely on it all day".
 */
class VirtualTseLoginModelTest {

    private val adminPin = "12345".toByteArray()
    private val timeAdminPin = "12345".toByteArray()

    @AfterEach
    fun tearDown() = VirtualTseTestSupport.detachDevice()

    @Test
    fun `v2 login evicts the previously logged in user`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            VirtualTseTestSupport.enableLogin(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)

            store.info().use { info ->
                assertEquals(
                    WormUserId.WORM_USER_TIME_ADMIN,
                    info.loggedInUser(),
                    "TimeAdmin login should have evicted Admin",
                )
            }
        }
    }

    @Test
    fun `v1 keeps both users logged in`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            VirtualTseTestSupport.enableLogin(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)
            store.user_login(WormUserId.WORM_USER_TIME_ADMIN, timeAdminPin)

            // On v1 the Admin session survives, which is what the current production
            // code relies on and what must keep working for the installed base.
            store.tse_ctss_enable()
        }
    }

    @Test
    fun `v2 reports a positive auto logout timeout while a user is logged in`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            VirtualTseTestSupport.enableLogin(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)
            store.info().use { info ->
                val remaining = info.loggedInUserAutoLogOutTimeout()
                assertTrue(remaining in 1..2400, "expected 1..2400s remaining, got $remaining")
            }
        }
    }

    @Test
    fun `v2 reports no logged in user before any login`() {
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            VirtualTseTestSupport.enableLogin(store)
            store.info().use { info ->
                assertEquals(WormUserId.WORM_USER_UNAUTHENTICATED, info.loggedInUser())
                assertEquals(0, info.loggedInUserAutoLogOutTimeout())
            }
        }
    }

    @Test
    fun `v1 reports no logged-in user even when one is - matching real hardware`() {
        VirtualTseTestSupport.attachDevice("v1")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            VirtualTseTestSupport.enableLogin(store)
            store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)

            store.info().use { info ->
                // Not a bug in the double: the SDK states worm_info_loggedInUser "for older
                // TSEs will always return WORM_USER_UNAUTHENTICATED". Reporting the true
                // session here would make the double more truthful than the hardware, so a
                // test could pass against it and be wrong about real v1 devices.
                assertEquals(
                    WormUserId.WORM_USER_UNAUTHENTICATED,
                    info.loggedInUser(),
                    "v1 hardware never reports the logged-in user",
                )
                // Likewise 0xffff, meaning "no idle logout on this generation" -- not 0.
                assertEquals(0xFFFF, info.loggedInUserAutoLogOutTimeout())
            }

            // ...while the session is genuinely active, which is what authorization uses.
            store.tse_ctss_enable()
        }
    }
    @Test
    fun `login is refused until the PUK has been changed`() {
        // The SDK: "Before any user can be logged in, the initial PUK must have been changed."
        // A fresh device must therefore reject a login outright — and as a state error, not an
        // authentication failure, so it does not even spend a login attempt. This is the guard
        // that a login-before-PUK-change bug would trip; without it such a bug passes silently.
        VirtualTseTestSupport.attachDevice("v2")
        WormStore(VirtualTseTestSupport.MOUNT_POINT).use { store ->
            val failure = assertFailsWith<WormException> {
                store.user_login(WormUserId.WORM_USER_ADMIN, adminPin)
            }
            assertEquals(WormError.WORM_ERROR_WRONG_STATE_NEEDS_PUK_CHANGE, failure.error())
        }
    }

}
