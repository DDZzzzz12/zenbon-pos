package org.example.project.features.customexport.domain.catalogue

/**
 * A subject paired with what one caller may see — a filtered view, never a doctored [ExportSubject],
 * whose `allOfferings` is derived and so cannot honestly be copied with fewer.
 */
data class VisibleSubject(
    val subject: ExportSubject,
    /**
     * Everything this caller may tick, of both kinds, in picker order.
     *
     * Typed [ExportOffering] rather than [ExportField]: an aggregate is a field to the operator — one
     * more cell in the picker, one more column in the file — and giving the two kinds separate lists
     * here would let a consumer render one and forget the other with nothing to say so. See
     * [ExportOffering] for why the sealed type is what makes that forgetting a compile error.
     */
    val fields: List<ExportOffering>,
)
