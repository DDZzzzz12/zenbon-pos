package org.example.project.features.partner.domain.repository

import org.example.project.features.partner.domain.models.BusinessPartner

/**
 * One group of parties that are probably the same company.
 *
 * [key] is what they agree on — a canonical VAT id, or a normalized name — so a reviewer can see
 * *why* they were put together rather than being asked to trust the grouping.
 *
 * Shares a file with the interface it is returned from, the way `RefreshTokenRow` sits beside
 * `RefreshTokenRepository`: a value type with no behaviour, meaningful only as that return.
 */
data class PartnerDuplicateGroup(
    val key: String,
    val partners: List<BusinessPartner>,
)

/** Party reads and the writes a merge needs. Requires an ambient transaction. */
interface BusinessPartnerRepository {
    /**
     * The party with this id, following [BusinessPartner.mergedInto] to the survivor.
     *
     * A merge keeps the losing row, so a bookmarked URL, an older client or a re-run export can
     * still address it. Following the pointer answers with the company they meant instead of an
     * empty shell. Returns null when the id does not exist.
     */
    fun findByIdFollowingMerges(id: Long): BusinessPartner?

    /**
     * Parties that are probably the same company, grouped by what they agree on.
     *
     * Already-merged parties are excluded: they have been dealt with, and offering them again would
     * make the list never empty.
     *
     * [limit] caps the number of **groups**, not the number of parties in them — a group is the unit
     * a reviewer acts on, so half a group would be worse than none.
     */
    fun findDuplicateGroups(limit: Int): List<PartnerDuplicateGroup>
}
