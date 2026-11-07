package org.example.project.admin.features.update.presentation

import org.example.project.core.domain.dto.update.UpdateDto

/**
 * Reduces everything the API reports to the single [UpdateSituation] the card renders.
 *
 * Pure on purpose: the whole point of this screen is that each condition gets its own actionable
 * wording, and a pure function is the only version of that a test can pin exhaustively.
 *
 * ### Why the order is the order
 *
 * [blocked] and [tokenKeyMissing] come from the *last action the operator took*, not from the
 * stored status, so they outrank it — a 409 they just earned is what they are looking at the screen
 * to understand.
 *
 * An undecryptable token on the private channel then outranks the recorded outcome. That outcome
 * may be a perfectly cheerful `UP_TO_DATE` written before the database was restored onto this host,
 * and showing it would answer the operator's question with the previous machine's answer.
 *
 * The tail deliberately derives from the *data* (`staged`, `availableVersion`) rather than from the
 * outcome word, so an outcome word this build has never heard of still lands somewhere truthful
 * instead of on an `else` branch that guesses.
 */
fun resolveUpdateSituation(
    status: UpdateDto.StatusDto,
    tokenKeyMissing: Boolean = false,
    blocked: Boolean = false,
): UpdateSituation {
    if (blocked) return UpdateSituation.BLOCKED
    if (tokenKeyMissing) return UpdateSituation.TOKEN_KEY_MISSING
    if (status.channel == PRIVATE_CHANNEL && status.tokenState == UpdateDto.TokenState.UNREADABLE) {
        return UpdateSituation.TOKEN_UNREADABLE
    }

    val outcome = status.lastCheckOutcome ?: return UpdateSituation.NEVER_CHECKED
    return when (outcome) {
        OUTCOME_AUTH_FAILED -> UpdateSituation.AUTH_FAILED
        OUTCOME_TOKEN_UNREADABLE -> UpdateSituation.TOKEN_UNREADABLE
        OUTCOME_UNREACHABLE -> UpdateSituation.UNREACHABLE
        OUTCOME_STAGING_FAILED -> UpdateSituation.STAGING_FAILED
        OUTCOME_ROLLED_BACK -> UpdateSituation.ROLLED_BACK
        // The artifact IS on disk, so the data-driven tail below would answer STAGED — truthfully,
        // and uselessly. What the operator needs to know is that the install was attempted and
        // refused, which is a fact only the outcome word carries.
        OUTCOME_SKIPPED -> UpdateSituation.AUTO_APPLY_BLOCKED
        else ->
            when {
                status.staged -> UpdateSituation.STAGED
                status.availableVersion != null -> UpdateSituation.AVAILABLE
                else -> UpdateSituation.UP_TO_DATE
            }
    }
}

private const val PRIVATE_CHANNEL = "PRIVATE"

// The words `UpdateServiceImpl` records. Duplicated rather than shared because they cross the wire
// as free-form strings, and a client that has not been redeployed must still resolve the ones it
// knows — which the `else` branch above is what makes safe.
private const val OUTCOME_AUTH_FAILED = "AUTH_FAILED"
private const val OUTCOME_TOKEN_UNREADABLE = "TOKEN_UNREADABLE"
private const val OUTCOME_UNREACHABLE = "UNREACHABLE"
private const val OUTCOME_STAGING_FAILED = "STAGING_FAILED"
private const val OUTCOME_ROLLED_BACK = "UPDATE_ROLLED_BACK"
private const val OUTCOME_SKIPPED = "UPDATE_SKIPPED"
