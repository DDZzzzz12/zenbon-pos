package org.example.project.features.bill.mapper

/**
 * The stornoed originals a bill re-bills — the ONE rule the relation chips ([BillRelationLoader])
 * and the DSFinV-K `references.csv` share.
 *
 * Nothing is stored for it. A "re-open the table" Storno hands the original's items back as copies
 * stamped `duplicated_from_bill_id` = the original; the bill that later settles any of them re-bills
 * that original. A Zahlartwechsel Neubuchung also holds `rebooks_bill_id`, and its copies carry the
 * same original — merging both into one distinct set is what keeps that original referenced once.
 */
object RebilledOriginals {
    /**
     * [rebooksId] (a Zahlartwechsel's own link) first, then the distinct non-null [itemSourceIds]
     * ascending — every original exactly once. A bill whose items are all new re-bills nothing.
     */
    fun of(
        rebooksId: Long?,
        itemSourceIds: Collection<Long?>,
    ): List<Long> = (listOfNotNull(rebooksId) + itemSourceIds.filterNotNull().sorted()).distinct()
}
