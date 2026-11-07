package org.example.project.admin.features.update

import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.features.update.data.remote.UpdateApiClient

/**
 * Records every call. [calls] is what the capability tests assert on — a gate that is declared but
 * never called leaves this list non-empty, which is the failure mode a test of the predicate alone
 * cannot see.
 */
class FakeUpdateApiClient : UpdateApiClient {
    val calls = mutableListOf<String>()
    val tokensSent = mutableListOf<String>()
    val settingsSent = mutableListOf<UpdateDto.UpdateSettingsRequestDto>()

    var status: UpdateDto.StatusDto = upToDateStatus()
    var settings: UpdateDto.SettingsDto = defaultSettings()
    var failWith: Throwable? = null

    override suspend fun status(): UpdateDto.StatusDto {
        calls += "status"
        return status
    }

    override suspend fun settings(): UpdateDto.SettingsDto {
        calls += "settings"
        return settings
    }

    override suspend fun saveSettings(request: UpdateDto.UpdateSettingsRequestDto): UpdateDto.SettingsDto {
        calls += "saveSettings"
        settingsSent += request
        failWith?.let { throw it }
        settings = settings.copy(channel = request.channel, autoApply = request.autoApply)
        status = status.copy(channel = request.channel, autoApply = request.autoApply)
        return settings
    }

    override suspend fun setToken(token: String): UpdateDto.SettingsDto {
        calls += "setToken"
        tokensSent += token
        failWith?.let { throw it }
        settings = settings.copy(tokenState = UpdateDto.TokenState.SET, tokenPreview = "••••" + token.takeLast(4))
        return settings
    }

    override suspend fun clearToken(): UpdateDto.SettingsDto {
        calls += "clearToken"
        failWith?.let { throw it }
        settings = settings.copy(tokenState = UpdateDto.TokenState.NONE, tokenPreview = null)
        return settings
    }

    override suspend fun checkNow(): UpdateDto.CheckResultDto {
        calls += "checkNow"
        failWith?.let { throw it }
        return UpdateDto.CheckResultDto(summary = "checked", status = status)
    }

    override suspend fun stageNow(): UpdateDto.CheckResultDto {
        calls += "stageNow"
        failWith?.let { throw it }
        // Persisted, like the real server: the progress poll re-reads status() and must see it staged.
        status = status.copy(staged = true)
        return UpdateDto.CheckResultDto(summary = "staged", status = status)
    }

    override suspend fun applyNow(): UpdateDto.ApplyStartedDto {
        calls += "applyNow"
        failWith?.let { throw it }
        return UpdateDto.ApplyStartedDto(version = status.availableVersion ?: "1.2.0", message = "installing")
    }

    companion object {
        fun upToDateStatus(): UpdateDto.StatusDto =
            UpdateDto.StatusDto(
                currentVersion = "1.1.0",
                channel = "PUBLIC",
                autoApply = false,
                tokenState = UpdateDto.TokenState.NONE,
                lastCheckedAt = "2026-09-01T02:00:00Z",
                lastCheckOutcome = "UP_TO_DATE",
                availableVersion = null,
                availableNotes = null,
                staged = false,
                lastBlockedReason = null,
            )

        fun defaultSettings(): UpdateDto.SettingsDto =
            UpdateDto.SettingsDto(
                channel = "PUBLIC",
                repoOwner = "DDZzzzz12",
                repoName = "zenbon-pos",
                apiBaseUrl = "https://api.github.com",
                autoApply = false,
                tokenState = UpdateDto.TokenState.NONE,
                tokenPreview = null,
                tokenSetAt = null,
            )
    }
}
