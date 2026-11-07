package org.example.project.features.appupdate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.features.appupdate.domain.AppUpdateException
import org.example.project.features.appupdate.domain.AppUpdateFailure
import org.example.project.features.appupdate.domain.AppUpdatePlatform
import org.example.project.features.appupdate.domain.AppUpdateState
import org.example.project.features.appupdate.presentation.viewmodels.AppUpdateViewModel
import org.example.project.features.update.data.remote.AppUpdateApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val offered = UpdateDto.AndroidClientDto(13, "1.2.0", "ab".repeat(32), 100L, "notes")

    private class FakeApi(
        var answer: () -> UpdateDto.AndroidClientDto,
    ) : AppUpdateApiClient {
        override suspend fun latestAndroidClient() = answer()

        override fun downloadUrl() = "http://server/api/update/client/android/download"
    }

    private class FakePlatform(
        var installed: Long? = 12,
        var canInstallNow: Boolean = true,
        var downloadFailure: AppUpdateFailure? = null,
        var installFailure: AppUpdateFailure? = null,
    ) : AppUpdatePlatform {
        val calls = mutableListOf<String>()

        override fun installedVersionCode() = installed

        override suspend fun download(
            url: String,
            info: UpdateDto.AndroidClientDto,
            onProgress: (Long, Long) -> Unit,
        ) {
            calls += "download"
            onProgress(info.sizeBytes, info.sizeBytes)
            downloadFailure?.let { throw AppUpdateException(it) }
        }

        override fun canInstall() = canInstallNow

        override fun openInstallPermissionSettings() {
            calls += "openSettings"
        }

        override suspend fun install() {
            calls += "install"
            installFailure?.let { throw AppUpdateException(it) }
        }

        override fun discardDownload() {
            calls += "discard"
        }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        api: FakeApi = FakeApi { offered },
        platform: FakePlatform = FakePlatform(),
    ) = AppUpdateViewModel(api, platform)

    @Test fun `a platform without self-update never asks the server`() =
        runTest {
            var asked = false
            val api =
                FakeApi {
                    asked = true
                    offered
                }
            val viewModel = vm(api = api, platform = FakePlatform(installed = null))
            viewModel.check(manual = true)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, viewModel.state.value)
            assertEquals(false, asked)
        }

    @Test fun `a higher versionCode becomes available`() =
        runTest {
            val viewModel = vm()
            viewModel.check()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Available(offered), viewModel.state.value)
        }

    @Test fun `the same versionCode is up to date and a quiet automatic check shows nothing`() =
        runTest {
            val auto = vm(platform = FakePlatform(installed = 13))
            auto.check(manual = false)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, auto.state.value)

            val manual = vm(platform = FakePlatform(installed = 13))
            manual.check(manual = true)
            advanceUntilIdle()
            assertEquals(AppUpdateState.UpToDate, manual.state.value)
        }

    @Test fun `an unreachable server is silent for an automatic check`() =
        runTest {
            val viewModel = vm(api = FakeApi { throw NetworkApiException(RuntimeException("down")) })
            viewModel.check(manual = false)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, viewModel.state.value)
            assertNull(viewModel.error.value)
        }

    @Test fun `an unreachable server is reported for a manual check`() =
        runTest {
            val viewModel = vm(api = FakeApi { throw NetworkApiException(RuntimeException("down")) })
            viewModel.check(manual = true)
            advanceUntilIdle()
            assertNotNull(viewModel.error.value)
        }

    @Test fun `download then install walks the states in order`() =
        runTest {
            val platform = FakePlatform()
            val viewModel = vm(platform = platform)
            viewModel.check()
            advanceUntilIdle()
            viewModel.startUpdate()
            advanceUntilIdle()

            assertEquals(listOf("download", "install"), platform.calls)
        }

    @Test fun `a missing install permission stops at ready-to-install and opens nothing by itself`() =
        runTest {
            val platform = FakePlatform(canInstallNow = false)
            val viewModel = vm(platform = platform)
            viewModel.check()
            advanceUntilIdle()
            viewModel.startUpdate()
            advanceUntilIdle()

            assertEquals(AppUpdateState.ReadyToInstall(offered, needsPermission = true), viewModel.state.value)
            assertEquals(listOf("download"), platform.calls)

            viewModel.openPermissionSettings()
            assertEquals(listOf("download", "openSettings"), platform.calls)
        }

    @Test fun `a hash mismatch fails with the installed app untouched`() =
        runTest {
            val platform = FakePlatform(downloadFailure = AppUpdateFailure.HASH_MISMATCH)
            val viewModel = vm(platform = platform)
            viewModel.check()
            advanceUntilIdle()
            viewModel.startUpdate()
            advanceUntilIdle()

            val failed = assertIs<AppUpdateState.Failed>(viewModel.state.value)
            assertEquals(AppUpdateFailure.HASH_MISMATCH, failed.failure)
            assertEquals(false, platform.calls.contains("install"))
        }

    @Test fun `cancelling the system dialog is a failure the user can retry`() =
        runTest {
            val platform = FakePlatform(installFailure = AppUpdateFailure.INSTALL_CANCELLED)
            val viewModel = vm(platform = platform)
            viewModel.check()
            advanceUntilIdle()
            viewModel.startUpdate()
            advanceUntilIdle()

            val failed = assertIs<AppUpdateState.Failed>(viewModel.state.value)
            assertEquals(AppUpdateFailure.INSTALL_CANCELLED, failed.failure)
            assertNotNull(failed.info)
        }

    @Test fun `an up-to-date check sweeps the APK a finished install left behind`() =
        runTest {
            val platform = FakePlatform(installed = 13)
            vm(platform = platform).check()
            advanceUntilIdle()
            assertEquals(true, platform.calls.contains("discard"))
        }

    @Test fun `dismissing discards the download and goes idle`() =
        runTest {
            val platform = FakePlatform()
            val viewModel = vm(platform = platform)
            viewModel.check()
            advanceUntilIdle()
            viewModel.dismiss()
            assertEquals(AppUpdateState.Idle, viewModel.state.value)
            assertEquals(true, platform.calls.contains("discard"))
        }

    private fun notStaged() = PosApiException(PosErrorCode.UPDATE_NOT_STAGED, 404, "nothing staged", "trace")

    @Test fun `a 404 means nothing staged and is never an error`() =
        runTest {
            val auto = vm(api = FakeApi { throw notStaged() })
            auto.check(manual = false)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, auto.state.value)
            assertNull(auto.error.value)

            val manual = vm(api = FakeApi { throw notStaged() })
            manual.check(manual = true)
            advanceUntilIdle()
            assertEquals(AppUpdateState.UpToDate, manual.state.value)
            assertNull(manual.error.value)
        }

    @Test fun `a real server error is reported for a manual check but never claims up to date`() =
        runTest {
            val viewModel = vm(api = FakeApi { throw PosApiException(PosErrorCode.UPDATE_NOT_STAGED, 500, "boom", "trace") })
            viewModel.check(manual = true)
            advanceUntilIdle()
            assertNotNull(viewModel.error.value)
            assertEquals(AppUpdateState.Idle, viewModel.state.value)
        }
}
