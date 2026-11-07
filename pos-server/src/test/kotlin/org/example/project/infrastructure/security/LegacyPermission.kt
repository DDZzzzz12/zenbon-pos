package org.example.project.infrastructure.security

/**
 * The retired per-employee grant vocabulary, kept alive only for the Stage 1 equivalence proof.
 *
 * `PermissionEnum` used to live in `shared/` and reach the JWT, `TokenClaims`, the employee DTOs and
 * both clients. Stage 2a retired the mechanism and every one of those uses became a value that was
 * always empty and never consulted — so the type was deleted from production entirely.
 *
 * It survives here, and only here, because `CapabilityEquivalenceTest` **models the old registry**
 * to prove Stage 1 changed no verdict. Deleting the vocabulary would have deleted the proof, which
 * is a worse trade than carrying fifteen lines of test-local enum.
 *
 * Only [EXCEPTION_SYSTEM_CONFIG] is ever constructed: it is the single grant the legacy rules
 * combined with a role, in the fifteen `PermissionOrRole(EXCEPTION_SYSTEM_CONFIG, MANAGER)` entries
 * that `LegacyRuleFixture` reproduces. The rest of the original enum — `fromId`, `fromCode`,
 * `getCoreRoles`, `isExceptionPermission` and the other eight members — had no callers left and is
 * not reproduced.
 *
 * **Do not grow this.** It is a historical record, not a mechanism. A new authorization concept goes
 * in `Capability`.
 */
enum class LegacyPermission {
    EXCEPTION_SYSTEM_CONFIG,
}
