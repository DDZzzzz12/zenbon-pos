package org.example.project.admin.features.update

import org.example.project.admin.features.update.presentation.UpdateSituation
import org.example.project.admin.features.update.presentation.resolveUpdateSituation
import org.example.project.core.domain.dto.update.UpdateDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every condition the update card can be in, pinned one by one.
 *
 * The two that matter most are negative assertions dressed as equality: a rejected token must not
 * resolve to [UpdateSituation.UP_TO_DATE], and a restored database must not either. Both would be
 * comfortable, plausible screens telling an operator to do nothing while their installation
 * silently stops receiving updates.
 */
class UpdateSituationTest {
    private fun status(
        outcome: String? = null,
        available: String? = null,
        staged: Boolean = false,
        channel: String = "PUBLIC",
        tokenState: UpdateDto.TokenState = UpdateDto.TokenState.NONE,
        blockedReason: String? = null,
    ) = UpdateDto.StatusDto(
        currentVersion = "1.1.0",
        channel = channel,
        autoApply = false,
        tokenState = tokenState,
        lastCheckedAt = "2026-09-01T02:00:00Z",
        lastCheckOutcome = outcome,
        availableVersion = available,
        availableNotes = null,
        staged = staged,
        lastBlockedReason = blockedReason,
    )

    @Test
    fun neverCheckedIsItsOwnState() {
        assertEquals(UpdateSituation.NEVER_CHECKED, resolveUpdateSituation(status(outcome = null)))
    }

    @Test
    fun upToDate() {
        assertEquals(UpdateSituation.UP_TO_DATE, resolveUpdateSituation(status(outcome = "UP_TO_DATE")))
    }

    @Test
    fun availableButNotStaged() {
        assertEquals(
            UpdateSituation.AVAILABLE,
            resolveUpdateSituation(status(outcome = "UPDATE_AVAILABLE", available = "1.2.0")),
        )
    }

    @Test
    fun aRolledBackInstallIsNotShownAsStaged() {
        assertEquals(
            UpdateSituation.ROLLED_BACK,
            resolveUpdateSituation(status(outcome = "UPDATE_ROLLED_BACK", available = "1.2.0", staged = true)),
        )
    }

    @Test
    fun stagedNotApplied() {
        assertEquals(
            UpdateSituation.STAGED,
            resolveUpdateSituation(status(outcome = "UPDATE_STAGED", available = "1.2.0", staged = true)),
        )
    }

    @Test
    fun aNightlyRunSkippedByThePreflightIsNotJustStaged() {
        // UPDATE_SKIPPED means the artifact is on disk AND the install was attempted and refused.
        // Rendering that as STAGED was the bug: a shop whose till is never idle at 03:00 read
        // "ready to install" every morning and never learned the server was trying and failing.
        assertEquals(
            UpdateSituation.AUTO_APPLY_BLOCKED,
            resolveUpdateSituation(status(outcome = "UPDATE_SKIPPED", available = "1.2.0", staged = true)),
        )
    }

    @Test
    fun aLiveRefusalOutranksLastNightsRecord() {
        // Both are true; only one of them is about the till the operator is standing at.
        assertEquals(
            UpdateSituation.BLOCKED,
            resolveUpdateSituation(status(outcome = "UPDATE_SKIPPED", available = "1.2.0", staged = true), blocked = true),
        )
    }

    @Test
    fun aRejectedTokenIsNeverUpToDate() {
        // THE POINT OF THE WHOLE FEATURE. A private repository answers 404 to an unauthorised
        // caller, so an expired PAT is indistinguishable from "no releases" unless it is told apart
        // deliberately. Collapsing these tells a shop it is current, forever.
        val resolved = resolveUpdateSituation(status(outcome = "AUTH_FAILED", channel = "PRIVATE"))
        assertEquals(UpdateSituation.AUTH_FAILED, resolved)
    }

    @Test
    fun anUndecryptableTokenOutranksAStaleCheerfulOutcome() {
        // A database restored onto another host keeps the previous machine's UP_TO_DATE row. The
        // token state is the newer, truer fact.
        assertEquals(
            UpdateSituation.TOKEN_UNREADABLE,
            resolveUpdateSituation(
                status(outcome = "UP_TO_DATE", channel = "PRIVATE", tokenState = UpdateDto.TokenState.UNREADABLE),
            ),
        )
    }

    @Test
    fun anUndecryptableTokenOnThePublicChannelIsHarmless() {
        // The public channel needs no credential, so a token nobody can open changes nothing about
        // whether this installation is current.
        assertEquals(
            UpdateSituation.UP_TO_DATE,
            resolveUpdateSituation(
                status(outcome = "UP_TO_DATE", channel = "PUBLIC", tokenState = UpdateDto.TokenState.UNREADABLE),
            ),
        )
    }

    @Test
    fun aMissingHostKeyOutranksEverythingExceptABlockedInstall() {
        assertEquals(
            UpdateSituation.TOKEN_KEY_MISSING,
            resolveUpdateSituation(status(outcome = "UP_TO_DATE"), tokenKeyMissing = true),
        )
    }

    @Test
    fun unreachable() {
        assertEquals(UpdateSituation.UNREACHABLE, resolveUpdateSituation(status(outcome = "UNREACHABLE")))
    }

    @Test
    fun stagingFailed() {
        assertEquals(
            UpdateSituation.STAGING_FAILED,
            resolveUpdateSituation(status(outcome = "STAGING_FAILED", available = "1.2.0")),
        )
    }

    @Test
    fun aBlockedInstallOutranksEverything() {
        assertEquals(
            UpdateSituation.BLOCKED,
            resolveUpdateSituation(status(outcome = "UPDATE_STAGED", staged = true), tokenKeyMissing = true, blocked = true),
        )
    }

    @Test
    fun anOutcomeWordThisBuildHasNeverHeardOfStillLandsSomewhereTruthful() {
        // A newer server may record a word this client does not know. Falling back to the DATA
        // (staged / availableVersion) rather than to an else-branch guess is what keeps that honest.
        assertEquals(
            UpdateSituation.STAGED,
            resolveUpdateSituation(status(outcome = "SOMETHING_NEW", available = "1.3.0", staged = true)),
        )
        assertEquals(UpdateSituation.UP_TO_DATE, resolveUpdateSituation(status(outcome = "SOMETHING_NEW")))
    }
}
