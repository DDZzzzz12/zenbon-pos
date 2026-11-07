package org.example.project.features.partner

import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.document.data.repository.DocumentRepositoryImpl
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierResolver
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `business_partner.tax_number` is the resolver's second rung and is compared with an exact `eq`,
 * so what the creditor paths store and what the debtor paths store has to be the same string for
 * the same input. It was not: `orNull` trimmed on the debtor side, the creditor side did not, and
 * the reader did not either — so a company entered through one path was invisible from the other.
 */
@DisplayName("Party tax number is canonicalized on every path")
class PartnerTaxNumberCanonicalTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()
    private val documents = DocumentRepositoryImpl()
    private val resolver = SupplierResolver(suppliers)

    @Test
    fun `the creditor and debtor paths store one key for the same messy input`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            transaction {
                suppliers.create(name = "Metro AG", taxNumber = " 12/345/67890 ")
                debtors.create(
                    shop = ShopInfo.all().first(),
                    name = "Elektro Schmidt",
                    taxNumber = " 12/345/67890 ",
                )
            }

            transaction {
                val creditorParty = Supplier.all().first().partner
                val debtorParty = Debtor.all().first().partner
                assertEquals("1234567890", creditorParty.taxNumber)
                assertEquals(creditorParty.taxNumber, debtorParty.taxNumber)
            }
        }
    }

    @Test
    fun `an invoice's tax number reaches a party created through the debtor path`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val partyId =
                transaction {
                    debtors
                        .create(
                            shop = ShopInfo.all().first(),
                            name = "Getränke Müller GmbH",
                            taxNumber = "12/345/67890",
                        ).partner.id.value
                }

            transaction {
                // The unslashed spelling of the same Steuernummer, padded the way an XML text node
                // hands it over. It is one number, so it must find the one company that has it.
                val invoice =
                    documents.createInvoice(
                        sellerName = "Mueller Getraenke",
                        sellerTaxNumber = " 1234567890 ",
                    )
                resolver.resolveAndLink(invoice)

                val resolvedParty = invoice.supplier?.partner
                assertEquals(partyId, resolvedParty?.id?.value)
                assertEquals(
                    1,
                    BusinessPartner.all().count().toInt(),
                    "the company already existed; a second one would split its ledger in two",
                )
            }
        }
    }
}
