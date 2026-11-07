package org.example.project.admin.features.update.presentation

import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.domain.enums.update.UpdateBlockedReason
import org.example.project.core.presentation.error.UiError

/**
 * Everything the update card renders.
 *
 * [canManage] is the client half of CLAUDE.md rule 8 — the server enforces `UPDATE_MANAGE` on every
 * write, and this is what stops the console offering controls that would only earn a 403. It is
 * resolved on every load rather than once in `init`, because adminApp ViewModels outlive a logout.
 *
 * [isBlocked] and [tokenKeyMissing] are *sticky answers to the operator's last action*, not
 * stored server state: a 409 has no place in the status read, but it is exactly what the operator
 * needs the screen to explain. Both clear on the next successful action.
 */
data class UpdateState(
    val status: UpdateDto.StatusDto? = null,
    val settings: UpdateDto.SettingsDto? = null,
    val isLoading: Boolean = false,
    val busy: UpdateAction? = null,
    val canManage: Boolean = false,
    /** The host has no `UPDATE_TOKEN_KEY`, so the private channel cannot work at all. */
    val tokenKeyMissing: Boolean = false,
    /** True when the last install attempt was refused by the preflight. */
    val isBlocked: Boolean = false,
    /**
     * Which condition refused it, when this build recognises the name the server sent.
     *
     * Null while [isBlocked] is true is a real case, not an oversight: a newer server may name a
     * blocker this build has never heard of, and "the till is not ready" is still a truthful thing
     * to say. Guessing the nearest known reason would not be.
     */
    val blockedReason: UpdateBlockedReason? = null,
    /** Set once an apply is under way; the server exits mid-response, so this is not an error. */
    val applyingVersion: String? = null,
    val error: UiError? = null,
) {
    /** The single condition the card explains, or null while the first load is still running. */
    val situation: UpdateSituation?
        get() = status?.let { resolveUpdateSituation(it, tokenKeyMissing, isBlocked) }

    /**
     * The blocker to name on the card: the one from the 409 the operator just earned, otherwise the
     * one the last nightly run recorded.
     *
     * The live answer wins because it describes the till *now*; the stored one is a report about
     * 03:00. Both resolve to the same [UpdateBlockedReason] and therefore to the same sentence, so
     * the screen needs one lookup, not two.
     */
    val activeBlocker: UpdateBlockedReason?
        get() = if (isBlocked) blockedReason else UpdateBlockedReason.parse(status?.lastBlockedReason)
}
