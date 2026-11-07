package org.example.project.features.update.domain.models

/**
 * The settings an operator may change from the admin panel, grouped so the repository keeps a
 * short parameter list rather than five positional arguments that are easy to transpose.
 *
 * The token is deliberately absent: it is written and cleared through its own methods so the
 * ciphertext columns can only ever move as a pair.
 *
 * The repository is absent for a different reason: it is a property of the [channel] rather than an
 * independent setting, so it is derived on save. Carrying it here would let a caller store a channel
 * and a repository that disagree, which is exactly the defect this shape removes.
 */
data class UpdateSettingsEdit(
    val channel: UpdateChannel,
    val apiBaseUrl: String,
    val autoApply: Boolean,
)
