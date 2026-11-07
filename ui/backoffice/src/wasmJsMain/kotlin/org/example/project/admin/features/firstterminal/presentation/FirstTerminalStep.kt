package org.example.project.admin.features.firstterminal.presentation

/**
 * The stations of the new-server first-terminal flow (Terminal Phase RB3 Part B).
 *
 * CREATE_TERMINAL → COMMISSION_TSE (fiscal only) → ISSUE_CODE → DONE.
 *
 * The terminal comes first because setup registers it as the TSE's first client — the back office
 * is no terminal itself. REGISTER_TSE is reached only when the TSE is already commissioned. A
 * non-fiscal terminal skips both TSE steps (it has no TSE client by design). While the TSE is DISABLED
 * — a fresh install — COMMISSION_TSE is still reached and also offers to continue without a TSE.
 */
enum class FirstTerminalStep {
    CREATE_TERMINAL,
    COMMISSION_TSE,
    REGISTER_TSE,
    ISSUE_CODE,
    DONE,
}
