package org.example.project.features.partner.domain.models

import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.features.supplier.domain.models.SupplierBankAccount
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/** Exposed DAO entity for a business partner. See [BusinessPartnerTable]. */
class BusinessPartner(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<BusinessPartner>(BusinessPartnerTable)

    var shop by ShopInfo referencedOn BusinessPartnerTable.shopId
    var name by BusinessPartnerTable.name
    var legalName by BusinessPartnerTable.legalName
    var vatId by BusinessPartnerTable.vatId
    var taxNumber by BusinessPartnerTable.taxNumber
    var street by BusinessPartnerTable.street
    var zip by BusinessPartnerTable.zip
    var city by BusinessPartnerTable.city
    var country by BusinessPartnerTable.country
    var email by BusinessPartnerTable.email
    var phone by BusinessPartnerTable.phone
    var normalizedName by BusinessPartnerTable.normalizedName
    var mergedInto by BusinessPartner optionalReferencedOn BusinessPartnerTable.mergedIntoPartnerId
    var isActive by BusinessPartnerTable.isActive
    var notes by BusinessPartnerTable.notes
    var createdAt by BusinessPartnerTable.createdAt
    var updatedAt by BusinessPartnerTable.updatedAt

    /**
     * Every IBAN known for this company, whichever role first saw it.
     *
     * Registration still goes through the creditor role, so today this is the same set as
     * `Supplier.bankAccounts` — the point is that it no longer has to be.
     */
    val bankAccounts by SupplierBankAccount referrersOn SupplierBankAccountTable.partnerId

    override fun toString(): String = "BusinessPartner(id=$id, name=$name, vatId=$vatId)"
}
