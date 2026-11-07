package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability

/**
 * Turning a stored key back into a field.
 *
 * One function, used by **both** the save path and the run path. Two lookups with two spellings is
 * how a definition ends up accepted by one and rejected by the other.
 */
object ExportFieldResolver {
    /**
     * [key] as a current offering key, then as a [ExportField.legacyKey]. Current always wins.
     *
     * Typed [ExportOffering], not [ExportField]: an aggregate is stored in the same column of the same
     * table as a column field, so a resolver that only knew about columns would report every saved
     * aggregate as a key the catalogue does not know — refused on save, refused on run, and shown as a
     * *broken* definition in the operator's list.
     *
     * **[ExportField.legacyKey] stays on the column kind alone**, and the legacy leg is simply a no-op
     * for an aggregate. A legacy key exists to survive a renamed *database column*; an aggregate key
     * is `<pathKey>~<aggKey>`, where the aggregate key is a name chosen in the catalogue and not read
     * off the schema, so renaming it is a decision somebody makes rather than a rename forced on them
     * — and the answer to it is to keep the old key. Giving [ExportOffering] a nullable `legacyKey`
     * that is always null on one of its two kinds would state a capability the aggregate side does not
     * have and cannot use.
     */
    fun fieldFor(
        key: String,
        fields: List<ExportOffering>,
    ): ExportOffering? =
        fields.firstOrNull { it.key == key }
            ?: fields.firstOrNull { (it as? ExportField)?.legacyKey == key }

    /**
     * What holding [key] requires, resolved the same way [fieldFor] resolves the field itself.
     *
     * The map [ExportSubject.capabilityRequirements] returns is keyed by the field's **current** key,
     * so a stored legacy key is absent from it. Reading that map directly with the stored key would
     * therefore make a legacy-keyed field either unknown (a refusal where the field exists) or —
     * worse, if a caller defaulted the missing entry to the empty set — a field that runs with **no
     * capability check at all**. So the key is resolved to a field first and the requirement is
     * looked up under that field's current key, which is the only key the map ever holds.
     *
     * Null means the subject does not offer this key under either spelling; an empty set is never
     * returned for a field it does offer, because every offered field carries at least its own
     * table's capability.
     */
    fun requirementsFor(
        key: String,
        fields: List<ExportOffering>,
        requirements: Map<String, Set<Capability>>,
    ): Set<Capability>? {
        val field = fieldFor(key, fields) ?: return null
        return requirements[field.key]
    }
}
