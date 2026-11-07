package org.example.project.features.partner

import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.data.service.SupplierServiceImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Editing a creditor writes the new identity onto the party.
 *
 * This is the half that had no guard at all: supplier updates bypass the repository and mutate the
 * entity inside [SupplierServiceImpl.update], so when the DTO began reporting the party, a rename
 * was accepted, answered 200, and silently discarded. The debtor path had tests that caught the
 * same defect; this one did not.
 *
 * The assertion reloads the party in a **separate transaction** on purpose. Reading it back off the
 * in-memory entity would pass even if nothing were ever flushed, which is exactly the failure being
 * excluded.
 */
@DisplayName("Editing a creditor writes the party")
class PartnerIdentityWriteTest {
    private val service = SupplierServiceImpl(SupplierRepositoryImpl(), ShopConfigurationManager)

    @Test
    fun `updating a supplier stores the new identity on its party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created =
                runBlocking {
                    service.create(SupplierDto.CreateRequestDto(name = "Metro AG", vatId = "DE123456789"))
                }

            runBlocking {
                service.update(
                    created.id,
                    SupplierDto.UpdateRequestDto(
                        name = "Metro Deutschland GmbH",
                        legalName = "Metro Deutschland GmbH & Co. KG",
                        vatId = "DE987654321",
                        city = "Düsseldorf",
                    ),
                )
            }

            transaction {
                val partyId =
                    Supplier
                        .findById(created.id)!!
                        .partner.id.value
                val party = BusinessPartner.findById(partyId)!!
                assertEquals("Metro Deutschland GmbH", party.name)
                assertEquals("Metro Deutschland GmbH & Co. KG", party.legalName)
                assertEquals("DE987654321", party.vatId)
                assertEquals("Düsseldorf", party.city)
            }
        }
    }

    @Test
    fun `a legal name given at creation reaches the party`() {
        // `SupplierRepository.create` takes no legal name, so the party it mints never saw one —
        // the service set it on the role only. Now that the DTO reads the party, that gap would
        // blank the legal name on every creditor.
        testApp(setupData = { TestData.createShopInfo() }) {
            val created =
                runBlocking {
                    service.create(
                        SupplierDto.CreateRequestDto(name = "Metro AG", legalName = "Metro AG & Co. KG"),
                    )
                }

            transaction {
                assertEquals("Metro AG & Co. KG", Supplier.findById(created.id)!!.partner.legalName)
            }
        }
    }
}
