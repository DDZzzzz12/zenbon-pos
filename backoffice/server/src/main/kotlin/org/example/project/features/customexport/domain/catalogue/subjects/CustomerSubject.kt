package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.customexport.domain.catalogue.ExportSubject

/**
 * Kunden — the CRM/loyalty records, as the flat list a shop uses for a mailing or hands to whoever
 * asks how many customers it has.
 *
 * The root is [CustomerTable]. Registering that table does more than create this subject: `debtor`
 * already points at it, so **Geschäftspartner** gains a "Kunde" group for free over
 * `debtor:by:partner_id.customers:via:customer_id`, and the loyalty record of an invoiced party can
 * be read next to its Debitorennummer without a line being written in [PartnerSubject]. That is the
 * catalogue's register-once-reachable-everywhere property, and `SubjectFieldAssemblyTest` pins the
 * exact key so it cannot quietly change under stored definitions.
 *
 * In the other direction the relation is to-many — `debtor.customer_id` carries a plain index, so one
 * loyalty record may back several debtor roles — which is why this subject reaches the outgoing
 * invoices only as **aggregates**, never as columns. A customer with four invoices stays one row.
 *
 * ## No shop column
 *
 * `customers` carries no shop. Stated rather than omitted, for the reason [ProductSubject] gives.
 *
 * ## The capability is [Capability.CUSTOMER_LIST], not [Capability.CUSTOMER_READ]
 *
 * `CustomerRules` splits the two along exactly the line this feature sits on: `CUSTOMER_READ` is the
 * till looking one person up by id or search, and `CUSTOMER_LIST` is `GET /api/customers` — walking
 * the whole base, which its KDoc calls back-office work with GDPR weight. An export is enumeration by
 * definition: every row at once, into a file that leaves the building. Registering it under the
 * narrower capability would hand a terminal login the entire customer database in one CSV, which is
 * defect D4 re-opened through a side door.
 */
object CustomerSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "kunde",
            label = "Kunden",
            root = CustomerTable,
            dateColumn = CustomerTable.createdAt,
            shopColumn = null,
        )
}
