package org.example.project.features.partner.data.repository

import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.partner.domain.repository.BusinessPartnerRepository
import org.example.project.features.partner.domain.repository.PartnerDuplicateGroup
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNull

/** Exposed implementation of [BusinessPartnerRepository]. Requires an ambient transaction. */
class BusinessPartnerRepositoryImpl : BusinessPartnerRepository {
    override fun findByIdFollowingMerges(id: Long): BusinessPartner? {
        // Bounded rather than `while (merged != null)`: a merge chain is normally one hop, and a
        // cycle -- which the schema does not prevent -- would otherwise hang the request thread.
        var current = BusinessPartner.findById(id) ?: return null
        repeat(MAX_MERGE_HOPS) {
            val next = current.mergedInto ?: return current
            current = next
        }
        return current
    }

    override fun findDuplicateGroups(limit: Int): List<PartnerDuplicateGroup> {
        // Ordered by id explicitly. `find { }` carries no ORDER BY, so both the order of the groups
        // and the order inside one would be whatever the engine happened to return -- which makes a
        // paged list unstable and a truncating `limit` arbitrary about which groups it drops.
        val live =
            BusinessPartner
                .find { BusinessPartnerTable.mergedIntoPartnerId.isNull() }
                .orderBy(BusinessPartnerTable.id to SortOrder.ASC)
                .toList()

        // Grouped in Kotlin rather than SQL. The set is small -- master data, one row per company we
        // deal with -- and a self-join with two OR-ed keys is markedly harder to read than this for
        // a table that will not reach four figures.
        val byVatId = live.filter { !it.vatId.isNullOrBlank() }.groupBy { it.vatId!! }
        val byName = live.filter { it.normalizedName.isNotBlank() }.groupBy { it.normalizedName }

        val groups = mutableListOf<PartnerDuplicateGroup>()
        val alreadyGrouped = mutableSetOf<Long>()

        // VAT id first: it is the stronger signal, so when a pair agrees on both, the group is
        // reported under the reason a reviewer should weigh.
        for ((key, members) in byVatId) {
            if (members.size < 2) continue
            groups += PartnerDuplicateGroup(key, members)
            members.forEach { alreadyGrouped += it.id.value }
        }
        for ((key, members) in byName) {
            if (members.size < 2) continue
            if (members.all { it.id.value in alreadyGrouped }) continue
            groups += PartnerDuplicateGroup(key, members)
            members.forEach { alreadyGrouped += it.id.value }
        }
        return groups.take(limit)
    }

    private companion object {
        /** A merge chain is one hop in practice; this only bounds a cycle the schema allows. */
        const val MAX_MERGE_HOPS = 16
    }
}
