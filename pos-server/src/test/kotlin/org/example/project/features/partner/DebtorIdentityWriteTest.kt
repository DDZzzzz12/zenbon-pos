package org.example.project.features.partner

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.data.service.DebtorServiceImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The debtor half of [PartnerIdentityWriteTest]: editing a debtor writes the new identity onto the
 * party, not only onto the role's own columns.
 *
 * Reloaded in a separate transaction for the same reason as the creditor test — an assertion made
 * against the in-memory entity would hold even if the write never reached the database.
 */
@DisplayName("Editing a debtor writes the party")
class DebtorIdentityWriteTest {
    private val service = DebtorServiceImpl(DebtorRepositoryImpl())

    @Test
    fun `updating a debtor stores the new identity on its party`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val created =
                runBlocking {
                    service.create(DebtorDto.CreateRequestDto(name = "Musterfirma GmbH"))
                }

            runBlocking {
                service.update(
                    created.id,
                    DebtorDto.UpdateRequestDto(
                        name = "Musterfirma Handels GmbH",
                        legalName = "Musterfirma Handels GmbH",
                        vatId = "DE111222333",
                        city = "Köln",
                    ),
                )
            }

            transaction {
                val partyId =
                    Debtor
                        .findById(created.id)!!
                        .partner.id.value
                val party = BusinessPartner.findById(partyId)!!
                assertEquals("Musterfirma Handels GmbH", party.name)
                assertEquals("Musterfirma Handels GmbH", party.legalName)
                assertEquals("DE111222333", party.vatId)
                assertEquals("Köln", party.city)
            }
        }
    }
}
