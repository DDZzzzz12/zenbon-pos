package org.example.project.features.update.domain.models

/**
 * Which GitHub repository releases are fetched from — and, since 2026-09-04, the repository itself.
 *
 * [PUBLIC] is the open-source repository and needs no credentials; [PRIVATE] is the internal one and
 * only works once a token has been stored (see `UpdateSettingsRepository.setToken`).
 *
 * **The coordinates live here on purpose.** They used to be free-standing `repo_owner`/`repo_name`
 * settings that the channel did not touch, and the admin panel had no control for them — so
 * selecting "Intern" stored `PRIVATE` while leaving the *public* repository configured, and the
 * private channel could not be reached at all. Everything the private channel exists for (the token
 * encryption, `UPDATE_TOKEN_KEY`, the 404-vs-`AuthFailed` distinction) was unreachable, with the UI
 * showing "Intern" selected and nothing indicating otherwise. Binding the repository to the channel
 * makes that state unrepresentable rather than merely discouraged.
 *
 * The host is still configurable (`api_base_url`) for a GitHub Enterprise deployment; only the
 * owner/name path is fixed, because for this product it is a property of the channel, not a choice.
 */
enum class UpdateChannel(
    val repoOwner: String,
    val repoName: String,
) {
    PUBLIC(repoOwner = "DDZzzzz12", repoName = "zenbon-pos"),
    PRIVATE(repoOwner = "DDZzzzz12", repoName = "POS-KMP"),
}
