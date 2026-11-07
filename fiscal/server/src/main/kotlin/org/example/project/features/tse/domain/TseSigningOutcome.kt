package org.example.project.features.tse.domain

import org.example.project.infrastructure.errors.PosException

/** Classification of a single TSE sign attempt, independent of the TseTransaction entity. */
sealed interface TseSignOutcome {
    /** signTransaction returned a (real or simulated) signature. */
    data object Signed : TseSignOutcome

    /** signTransaction returned null (DISABLED service — no signing performed). */
    data object NoSignature : TseSignOutcome

    /** signTransaction threw (e.g. TSE_UNREACHABLE). */
    data class Failed(
        val error: PosException,
    ) : TseSignOutcome
}
