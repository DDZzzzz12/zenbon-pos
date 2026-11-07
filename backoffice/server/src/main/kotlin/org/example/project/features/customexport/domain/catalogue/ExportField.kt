package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability
import org.jetbrains.exposed.v1.core.Column

/**
 * One field an operator may tick.
 *
 * [column] is a `Column<*>` and not a general `Expression<*>` on purpose: a column knows the table it
 * belongs to, which is what lets the denylist test prove that no declared field reaches denied data.
 * A computed expression could not be checked, so the catalogue does not allow one.
 *
 * @property key stable identifier, e.g. `partner.vat_id`. **This is what a saved definition stores**,
 *   so renaming a database column costs one edit here rather than breaking every stored export.
 * @property label German fallback shown when the client has no translation for [key]. The key is
 *   canonical; the adminApp screen localises from it.
 * @property capability what the viewer must hold to see this field at all.
 * @property legacyKey a key this field used to be published under, copied from
 *   [ExportColumn.legacyKey] and honoured by [ExportFieldResolver] when resolving a stored
 *   definition. **Verbatim, never prefixed** with the subject or path this field is reached through:
 *   a legacy key exists precisely because the old key did not follow today's scheme, so prefixing it
 *   with today's prefix would produce a key that was never published and repair nothing. The cost is
 *   that a renamed column on a table reachable by two paths needs its legacy key spelled out for the
 *   route that actually shipped — which is the honest position, because only one of them was stored.
 */
data class ExportField(
    override val key: String,
    override val label: String,
    override val type: ExportFieldType,
    override val capability: Capability,
    val column: Column<*>,
    /**
     * The picker's section heading for this field, or null for a column of the subject's own table.
     *
     * Display only, and deliberately independent of how the field is joined: it lets a table that is
     * always joined be grouped exactly like one joined on demand.
     */
    override val group: String? = null,
    val legacyKey: String? = null,
) : ExportOffering
