package org.example.project.features.customexport.domain.catalogue

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.subjects.BillSubject
import org.example.project.features.customexport.domain.catalogue.subjects.CustomerSubject
import org.example.project.features.customexport.domain.catalogue.subjects.IncomingInvoiceSubject
import org.example.project.features.customexport.domain.catalogue.subjects.KassenbuchSubject
import org.example.project.features.customexport.domain.catalogue.subjects.PartnerSubject
import org.example.project.features.customexport.domain.catalogue.subjects.ProductSubject
import org.example.project.features.customexport.domain.catalogue.subjects.SalesInvoiceSubject
import org.example.project.features.customexport.domain.catalogue.subjects.StaffSubject
import org.example.project.features.customexport.domain.catalogue.subjects.VoucherSubject

/**
 * Every subject an operator may export.
 *
 * Subjects are declared in code and their fields come from [ExportTableRegistry], which is curated by
 * hand. Nothing here is reflected wholesale from the schema: that is what makes per-field capability
 * gating expressible at all — you cannot gate what you have not enumerated. What *is* derived is which
 * tables a subject can reach, because the foreign keys already say so and a second statement of them
 * would drift.
 */
object ExportCatalogue {
    val subjects: List<ExportSubject> =
        listOf(
            PartnerSubject.subject,
            IncomingInvoiceSubject.subject,
            SalesInvoiceSubject.subject,
            ProductSubject.subject,
            CustomerSubject.subject,
            VoucherSubject.subject,
            BillSubject.subject,
            KassenbuchSubject.subject,
            StaffSubject.subject,
        )

    fun subject(key: String): ExportSubject? = subjects.firstOrNull { it.key == key }

    /**
     * The catalogue as this caller may see it.
     *
     * Filtered by [ExportSubject.offeringsVisibleTo] — which needs every capability on a relation
     * field's path, not only the one the field's own table carries, and which applies that same rule
     * to an aggregate — then subjects with nothing left are dropped, because offering a subject whose
     * every field is hidden invites the operator to build an export of nothing. "Nothing left" counts
     * both kinds: a subject reduced to aggregates alone is still worth offering.
     *
     * This is advisory in one direction only: it decides what a client is *offered*. The server
     * re-checks on save and again on run, because a definition outlives whoever built it and a client
     * is not trusted to have filtered honestly.
     */
    fun visibleTo(held: Set<Capability>): List<VisibleSubject> =
        subjects
            .map { subject -> VisibleSubject(subject, subject.offeringsVisibleTo(held)) }
            .filter { it.fields.isNotEmpty() }
}
