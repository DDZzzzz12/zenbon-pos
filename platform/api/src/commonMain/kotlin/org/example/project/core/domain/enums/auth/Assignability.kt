package org.example.project.core.domain.enums.auth

import kotlinx.serialization.Serializable

/**
 * Whether a [Capability] may be placed in an operator-created permission group.
 *
 * [RESERVED] marks the fiscally and structurally critical capabilities: they may appear only in
 * seeded system groups, and the Stage 5 management API rejects them in custom groups. This is the
 * same philosophy as the `pos-tse/` protected module — the dangerous set is not reachable by
 * ordinary configuration.
 *
 * Stage 1 only carries the flag; nothing enforces it until the group API exists.
 */
@Serializable
enum class Assignability {
    /** May appear in any permission group, including operator-created ones. */
    ASSIGNABLE,

    /** May appear only in seeded system groups. */
    RESERVED,
}
