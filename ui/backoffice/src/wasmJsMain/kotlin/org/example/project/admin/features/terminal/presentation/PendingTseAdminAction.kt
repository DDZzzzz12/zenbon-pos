package org.example.project.admin.features.terminal.presentation

/** What an admin-PIN unlock retries once the TSE admin sitting is open. */
enum class PendingTseAdminAction {
    /** `POST /{id}/tse-register` — the explicit (repair) registration. */
    REGISTER_TSE,

    /** `POST /{id}/pairing-code` with `registerTseClient = true`. */
    ISSUE_PAIRING_CODE,

    /** `POST /{id}/tse-deregister` — removing a retired terminal's TSE client. */
    DEREGISTER_TSE,
}
