package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeRestoredPathsBackupApiClient
import org.example.project.admin.features.dashboard.presentation.sections.RestoredPathsViewModel
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision
import org.example.project.core.domain.enums.setting.RestoredPathSetting
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val datev =
    RestoredPathsDto.PathDto(RestoredPathSetting.DATEV_EXPORT_PATH, "/Users/original/Zenbon POS/exports", "/home/kasse/Zenbon POS/exports")
private val backup =
    RestoredPathsDto.PathDto(RestoredPathSetting.BACKUP_PATH, "/Volumes/nas/backups", "/home/kasse/Zenbon POS/backups")

private val documents =
    RestoredPathsDto.PathDto(
        RestoredPathSetting.DOCUMENT_STORAGE_PATH,
        "/Users/original/Zenbon POS/documents",
        "/home/kasse/Zenbon POS/documents",
        keepAllowed = false,
    )

class RestoredPathsViewModelTest {
    @Test
    fun documentPath_keepIsNeverSent_useDefaultIs() =
        runTest {
            val api = FakeRestoredPathsBackupApiClient(pending = listOf(documents))
            val vm = RestoredPathsViewModel(api)
            vm.refresh()

            vm.decide(RestoredPathSetting.DOCUMENT_STORAGE_PATH, RestoredPathDecision.KEEP)
            assertTrue(api.decisionsReceived.isEmpty(), "KEEP is not offered for the document path")
            assertEquals(listOf(documents), vm.state.paths)

            vm.decide(RestoredPathSetting.DOCUMENT_STORAGE_PATH, RestoredPathDecision.USE_DEFAULT)
            assertEquals(listOf(documents), api.resetToDefault)
            assertTrue(vm.state.justResolved)
        }

    @Test
    fun nothingPending_showsNothing() =
        runTest {
            val vm = RestoredPathsViewModel(FakeRestoredPathsBackupApiClient(pending = emptyList()))
            vm.refresh()
            assertFalse(vm.state.isLoading)
            assertTrue(vm.state.paths.isEmpty())
            assertFalse(vm.state.justResolved)
        }

    @Test
    fun pendingPaths_areListedForDecision() =
        runTest {
            val vm = RestoredPathsViewModel(FakeRestoredPathsBackupApiClient(pending = listOf(datev, backup)))
            vm.refresh()
            assertEquals(listOf(datev, backup), vm.state.paths)
        }

    @Test
    fun decidingEachPath_sendsTheDecisionAndClearsTheNoticeWhenDone() =
        runTest {
            val api = FakeRestoredPathsBackupApiClient(pending = listOf(datev, backup))
            val vm = RestoredPathsViewModel(api)
            vm.refresh()

            vm.decide(RestoredPathSetting.DATEV_EXPORT_PATH, RestoredPathDecision.USE_DEFAULT)
            assertEquals(listOf(backup), vm.state.paths, "one decided, one still pending")
            assertFalse(vm.state.justResolved)
            assertNull(vm.state.deciding, "buttons are enabled again after the answer")

            vm.decide(RestoredPathSetting.BACKUP_PATH, RestoredPathDecision.KEEP)
            assertTrue(vm.state.paths.isEmpty())
            assertTrue(vm.state.justResolved, "the confirmation replaces the notice")
            assertEquals(
                listOf(
                    RestoredPathsDto.DecisionDto(RestoredPathSetting.DATEV_EXPORT_PATH, RestoredPathDecision.USE_DEFAULT),
                    RestoredPathsDto.DecisionDto(RestoredPathSetting.BACKUP_PATH, RestoredPathDecision.KEEP),
                ),
                api.decisionsReceived,
            )
            assertEquals(listOf(datev), api.resetToDefault)
        }

    @Test
    fun aFailedLoad_isSurfacedAsAnError() =
        runTest {
            val api = FakeRestoredPathsBackupApiClient(pending = listOf(datev), failNextRead = NetworkApiException(RuntimeException("offline")))
            val vm = RestoredPathsViewModel(api)
            vm.refresh()
            assertNotNull(vm.state.error)
            vm.clearError()
            vm.refresh()
            assertEquals(listOf(datev), vm.state.paths)
        }
}
