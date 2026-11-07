package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.domain.enums.setting.RestoredPathDecision

/**
 * Models the server's review: [pending] shrinks by every decision; `USE_DEFAULT` decisions are
 * recorded in [resetToDefault]. Only the two restored-path calls are implemented.
 */
class FakeRestoredPathsBackupApiClient(
    var pending: List<RestoredPathsDto.PathDto>,
    var failNextRead: Throwable? = null,
) : BackupApiClient {
    val resetToDefault = mutableListOf<RestoredPathsDto.PathDto>()
    val decisionsReceived = mutableListOf<RestoredPathsDto.DecisionDto>()

    override suspend fun getRestoredPaths(): RestoredPathsDto.ReviewDto {
        failNextRead?.let {
            failNextRead = null
            throw it
        }
        return review()
    }

    override suspend fun resolveRestoredPaths(request: RestoredPathsDto.ResolveRequestDto): RestoredPathsDto.ReviewDto {
        decisionsReceived += request.decisions
        val decided = request.decisions.associate { it.setting to it.decision }
        resetToDefault += pending.filter { decided[it.setting] == RestoredPathDecision.USE_DEFAULT }
        pending = pending.filter { it.setting !in decided }
        return review()
    }

    private fun review() = RestoredPathsDto.ReviewDto(pending = pending.isNotEmpty(), paths = pending)

    override suspend fun createBackup(request: BackupRestoreDto.CreateBackupRequestDto?) = throw NotImplementedError()

    override suspend fun restoreBackup(
        backupData: ByteArray,
        filename: String,
    ) = throw NotImplementedError()

    override suspend fun getBackupConfig() = throw NotImplementedError()

    override suspend fun updateBackupConfig(config: BackupRestoreDto.UpdateBackupConfigDto) = throw NotImplementedError()

    override suspend fun createBackupToServer(request: BackupRestoreDto.CreateBackupToServerRequestDto) = throw NotImplementedError()

    override suspend fun restoreFromServerFile(filePath: String) = throw NotImplementedError()

    override suspend fun listDirectories(path: String?) = throw NotImplementedError()

    override suspend fun listBackups(directory: String?) = throw NotImplementedError()
}
