package org.example.project.features.update.domain.service

import org.example.project.core.domain.dto.update.UpdateDto

/**
 * Orchestrates one update check: ask GitHub, stage what is newer, and -- only when the operator
 * has opted in and the shop is idle -- install it.
 *
 * The pieces it drives (`GithubReleaseClient`, `UpdateStager`, `UpdatePreflight`, `UpdateApplier`)
 * each own one step and none of them knows about the others. This is where the order between them
 * lives, and the order is the policy: nothing is installed that was not first verified against its
 * published checksum, and nothing is installed at all unless auto-apply is on.
 *
 * ### The manual endpoints are three deliberate steps, not one
 *
 * [checkNow] asks and records. [stageNow] additionally downloads and verifies. [applyNow] installs
 * what was staged. Splitting them is what lets an operator learn that a release exists without
 * spending two hundred megabytes on it, and stage it during the day without restarting the till in
 * the middle of service. [runScheduledCheck] is the nightly job and remains the only path that can
 * install unattended -- and only with `autoApply` on.
 */
interface UpdateService {
    /**
     * Runs one nightly check end to end and returns the operator-facing summary the scheduler
     * stores on the execution row -- `"up to date (1.1.0)"`,
     * `"1.2.0 available, staged, not applied (auto-apply off)"`, `"1.2.0 staged, skipped: open business"`.
     *
     * Never throws for an expected condition. A rejected token, an unreachable GitHub and a
     * blocked preflight are all outcomes to record and report, not failures: the scheduler would
     * paint them red every night and an operator would stop reading them.
     */
    suspend fun runScheduledCheck(): String

    /** The running build plus whatever the last check recorded. Never touches the network. */
    suspend fun status(): UpdateDto.StatusDto

    /** The update source as configured, with the token masked to its last four characters. */
    suspend fun settings(): UpdateDto.SettingsDto

    /**
     * Replaces the editable settings.
     *
     * @throws org.example.project.infrastructure.errors.PosException 400 when the request names an
     *   update source this server refuses to fetch from -- see `UpdateSourceValidator`.
     */
    suspend fun saveSettings(request: UpdateDto.UpdateSettingsRequestDto): UpdateDto.SettingsDto

    /**
     * Seals a new GitHub token with this host's key.
     *
     * @throws org.example.project.infrastructure.errors.PosException 409 when no `UPDATE_TOKEN_KEY`
     *   is provisioned, because a token sealed with nothing could never be read back.
     */
    suspend fun setToken(token: String): UpdateDto.SettingsDto

    /** Removes the stored token. Idempotent. */
    suspend fun clearToken(): UpdateDto.SettingsDto

    /** Asks GitHub what the latest release is and records it. Downloads nothing. */
    suspend fun checkNow(): UpdateDto.CheckResultDto

    /** Asks, then downloads and verifies the artifact against its published checksum. Never installs. */
    suspend fun stageNow(): UpdateDto.CheckResultDto

    /**
     * Installs the staged artifact and exits; systemd brings the new binary up.
     *
     * @throws org.example.project.infrastructure.errors.PosException 409 naming the preflight
     *   blocker, or 409 when nothing is staged.
     */
    suspend fun applyNow(): UpdateDto.ApplyStartedDto
}
