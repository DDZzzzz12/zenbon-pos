package org.example.project.admin.features.bank

import org.example.project.admin.features.bank.presentation.BankAccountsAccess
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.domain.enums.auth.Capability
import kotlin.test.Test
import kotlin.test.assertEquals

/** T9-21: the Bankkonten write controls follow the capabilities the server's rules require. */
class BankAccountsAccessTest {
    @Test
    fun a_read_only_group_is_offered_no_write_action() {
        val access = BankAccountsAccess.of(SetAccessPolicy(setOf(Capability.CREDITOR_READ)))

        assertEquals(BankAccountsAccess(mayManageAccounts = false, mayImportStatements = false), access)
    }

    @Test
    fun creditor_write_offers_account_management_and_document_write_offers_the_import() {
        assertEquals(
            BankAccountsAccess(mayManageAccounts = true, mayImportStatements = false),
            BankAccountsAccess.of(SetAccessPolicy(setOf(Capability.CREDITOR_READ, Capability.CREDITOR_WRITE))),
        )
        assertEquals(
            BankAccountsAccess(mayManageAccounts = false, mayImportStatements = true),
            BankAccountsAccess.of(SetAccessPolicy(setOf(Capability.CREDITOR_READ, Capability.DOCUMENT_WRITE))),
        )
    }
}
