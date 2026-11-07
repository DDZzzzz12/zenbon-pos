package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.ExportToggle
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull

/**
 * Geschäftspartner — the company master data an operator most often wants as a flat list
 * (a supplier phone list, an address sheet for the tax advisor).
 *
 * The root is [BusinessPartnerTable]. Nothing else is listed: the columns come from that table's
 * registry entry and the related tables from the foreign-key graph, so a role (creditor, debtor)
 * reachable from the party is offered as its own group without a line being written here.
 *
 * Every field is gated by [Capability.CREDITOR_READ] — a company record is creditor-side master data,
 * and that is what the existing partner routes require (`PartnerRules`). Exporting a field must not be
 * an easier way to read it than the screen that shows it.
 */
object PartnerSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "partner",
            label = "Geschäftspartner",
            root = BusinessPartnerTable,
            dateColumn = BusinessPartnerTable.createdAt,
            shopColumn = BusinessPartnerTable.shopId,
            toggles =
                listOf(
                    ExportToggle(
                        key = "partner.active_only",
                        label = "Nur aktive",
                        predicate = { BusinessPartnerTable.isActive eq true },
                    ),
                ),
            // A merged-away party is not a company any more. Listing it would hand the advisor the
            // duplicate its operator already resolved, so it is excluded whatever the operator ticked.
            //
            // `.isNull()` and not `eq null`: the nullable `EntityID` reference has no resolvable
            // `eq null` overload.
            basePredicate = { BusinessPartnerTable.mergedIntoPartnerId.isNull() },
        )
}
