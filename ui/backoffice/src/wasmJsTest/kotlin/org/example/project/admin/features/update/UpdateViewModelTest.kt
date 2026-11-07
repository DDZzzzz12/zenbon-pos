package org.example.project.admin.features.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.data.LocalStorageKeyValueStore
import org.example.project.admin.features.update.presentation.UpdateSituation
import org.example.project.admin.features.update.presentation.UpdateViewModel
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.update.UpdateBlockedReason
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Both keys are written every time, so a value left in localStorage by a previous case cannot leak. */
    private fun store(held: Set<Capability>): TokenStore =
        TokenStore(LocalStorageKeyValueStore()).apply {
            role = "ADMIN"
            capabilities = held.map { it.name }.toSet()
        }

    private val reader = setOf(Capability.UPDATE_READ)
    private val manager = setOf(Capability.UPDATE_READ, Capability.UPDATE_MANAGE)

    @Test
    fun `load fills status and settings`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            assertEquals(
                "1.1.0",
                vm.state.value.status
                    ?.currentVersion,
            )
            assertEquals(
                "zenbon-pos",
                vm.state.value.settings
                    ?.repoName,
            )
            assertEquals(UpdateSituation.UP_TO_DATE, vm.state.value.situation)
            assertTrue(vm.state.value.canManage)
        }

    @Test
    fun `a download already under way is watched until it ends, and the settled status is read`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            api.status =
                api.status.copy(
                    availableVersion = "1.2.0",
                    download = UpdateDto.DownloadProgressDto(UpdateDto.DownloadPhase.SERVER, 10, 100),
                )
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            runCurrent()
            assertEquals(
                10L,
                vm.state.value.status
                    ?.download
                    ?.bytesDone,
            )

            // The run ends on the server while this page is still looking at the old picture.
            api.status = api.status.copy(download = null, staged = true)
            advanceUntilIdle()

            assertNull(
                vm.state.value.status
                    ?.download,
            )
            assertTrue(
                vm.state.value.status
                    ?.staged == true,
            )
        }

    // THE GATE, on the path an operator actually drives. A test of `holds(UPDATE_MANAGE)` alone
    // would still pass if nothing ever called it; asserting the fake recorded no request is what
    // proves the guard is wired into the action rather than merely declared next to it.
    @Test
    fun `without UPDATE_MANAGE no write ever reaches the network`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(reader))
            vm.load()
            advanceUntilIdle()
            val afterLoad = api.calls.toList()

            vm.checkNow()
            vm.stageNow()
            vm.applyNow()
            vm.saveToken("github_pat_11ABCDEFGH")
            vm.clearToken()
            vm.setAutoApply(true)
            vm.setChannel("PRIVATE")
            advanceUntilIdle()

            assertEquals(afterLoad, api.calls, "a reader must not be able to reach any write route")
            assertTrue(api.tokensSent.isEmpty())
            assertFalse(vm.state.value.canManage)
        }

    @Test
    fun `with UPDATE_MANAGE the actions do reach the network`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            vm.checkNow()
            advanceUntilIdle()
            vm.stageNow()
            advanceUntilIdle()

            assertTrue("checkNow" in api.calls)
            assertTrue("stageNow" in api.calls)
            assertTrue(
                vm.state.value.status
                    ?.staged == true,
            )
        }

    @Test
    fun `a blocked install names the blocker instead of raising a generic error`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            api.status = api.status.copy(availableVersion = "1.2.0", staged = true, lastCheckOutcome = "UPDATE_STAGED")
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            api.failWith =
                PosApiException(
                    code = PosErrorCode.UPDATE_BLOCKED,
                    httpStatus = 409,
                    message = "update not installed: open business",
                    traceId = null,
                    fieldErrors = listOf(FieldError(field = "preflight", code = "OPEN_BUSINESS")),
                )
            vm.applyNow()
            advanceUntilIdle()

            assertEquals(UpdateBlockedReason.OPEN_BUSINESS, vm.state.value.blockedReason)
            assertEquals(UpdateSituation.BLOCKED, vm.state.value.situation)
            // The card says "there are still open bills"; a dialog repeating the server's English
            // would only say it again, worse.
            assertNull(vm.state.value.error)
        }

    @Test
    fun `a nightly run the preflight refused is visible on a plain load`() =
        runTest(dispatcher) {
            // Nobody pressed anything. The screen used to say "staged, ready to install" here,
            // which is exactly how a shop stays three versions behind without ever being told.
            val api = FakeUpdateApiClient()
            api.status =
                api.status.copy(
                    lastCheckOutcome = "UPDATE_SKIPPED",
                    availableVersion = "1.2.0",
                    staged = true,
                    lastBlockedReason = "OPEN_BUSINESS",
                )
            val vm = UpdateViewModel(api, store(manager))

            vm.load()
            advanceUntilIdle()

            assertEquals(UpdateSituation.AUTO_APPLY_BLOCKED, vm.state.value.situation)
            assertEquals(UpdateBlockedReason.OPEN_BUSINESS, vm.state.value.activeBlocker)
        }

    @Test
    fun `a stored blocker this build does not recognise names nothing rather than the nearest guess`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            api.status =
                api.status.copy(
                    lastCheckOutcome = "UPDATE_SKIPPED",
                    availableVersion = "1.2.0",
                    staged = true,
                    lastBlockedReason = "SOME_FUTURE_REASON",
                )
            val vm = UpdateViewModel(api, store(manager))

            vm.load()
            advanceUntilIdle()

            assertEquals(UpdateSituation.AUTO_APPLY_BLOCKED, vm.state.value.situation)
            assertNull(vm.state.value.activeBlocker)
        }

    @Test
    fun `a blocker this build does not recognise still reads as blocked`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            api.status = api.status.copy(availableVersion = "1.2.0", staged = true)
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            api.failWith =
                PosApiException(
                    code = PosErrorCode.UPDATE_BLOCKED,
                    httpStatus = 409,
                    message = "update not installed: something new",
                    traceId = null,
                    fieldErrors = listOf(FieldError(field = "preflight", code = "A_REASON_ADDED_LATER")),
                )
            vm.applyNow()
            advanceUntilIdle()

            assertNull(vm.state.value.blockedReason, "guessing the nearest known reason would be worse than saying nothing")
            assertEquals(UpdateSituation.BLOCKED, vm.state.value.situation)
        }

    @Test
    fun `a dropped connection during apply is progress, not failure`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            api.status = api.status.copy(availableVersion = "1.2.0", staged = true)
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            // The server writes the response, swaps the jar and exits; the socket dies mid-flight.
            api.failWith = NetworkApiException(RuntimeException("closed"))
            vm.applyNow()
            advanceUntilIdle()

            assertEquals("1.2.0", vm.state.value.applyingVersion)
            assertNull(vm.state.value.error, "telling the operator the install failed as it began is the defect")
        }

    @Test
    fun `a host with no update key is told so rather than shown a server error`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            api.failWith =
                PosApiException(
                    code = PosErrorCode.UPDATE_TOKEN_KEY_MISSING,
                    httpStatus = 409,
                    message = "no UPDATE_TOKEN_KEY is provisioned on this host",
                    traceId = null,
                )
            vm.saveToken("github_pat_11ABCDEFGH")
            advanceUntilIdle()

            assertTrue(vm.state.value.tokenKeyMissing)
            assertEquals(UpdateSituation.TOKEN_KEY_MISSING, vm.state.value.situation)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `the token is sent but never held in state`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            vm.saveToken("github_pat_11ABCDEFGH_secret")
            advanceUntilIdle()

            assertEquals(listOf("github_pat_11ABCDEFGH_secret"), api.tokensSent)
            // The read DTO has no token property at all, so there is nowhere for it to land — this
            // pins that the ViewModel did not invent one.
            assertFalse(
                vm.state.value
                    .toString()
                    .contains("github_pat_11ABCDEFGH_secret"),
            )
        }

    @Test
    fun `toggling auto-apply sends the whole settings block back`() =
        runTest(dispatcher) {
            val api = FakeUpdateApiClient()
            val vm = UpdateViewModel(api, store(manager))
            vm.load()
            advanceUntilIdle()

            vm.setAutoApply(true)
            advanceUntilIdle()

            assertEquals(1, api.settingsSent.size)
            assertTrue(api.settingsSent.single().autoApply)
            // Used to assert the echoed repoOwner. That echo WAS the defect: the panel had no
            // control for the repository, so it sent back whatever it had read, and toggling the
            // channel stored PRIVATE beside the public repository. The request carries no
            // repository at all now, so the invariant worth pinning is that an unrelated edit
            // leaves the channel alone.
            assertEquals("PUBLIC", api.settingsSent.single().channel)
            assertTrue(
                vm.state.value.status
                    ?.autoApply == true,
            )
        }
}
