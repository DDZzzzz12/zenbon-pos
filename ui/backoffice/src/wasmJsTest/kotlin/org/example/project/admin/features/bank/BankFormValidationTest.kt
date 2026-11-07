package org.example.project.admin.features.bank

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.bank.presentation.AssignStatementField
import org.example.project.admin.features.bank.presentation.BankAccountsViewModel
import org.example.project.admin.features.bank.presentation.validateAssignStatement
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bank dialogs keep their primary action enabled and validate on the click instead. */
@OptIn(ExperimentalCoroutinesApi::class)
class BankFormValidationTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRefusedCreate_bumpsTheAttempt_andEditingTheIbanClearsItsError() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.openCreateDialog()

            vm.createAccount(BankAccountDto.CreateRequestDto(iban = ""))
            advanceUntilIdle()
            assertEquals(1, vm.state.createAttempt, "the dialog focuses the IBAN on this bump")
            assertTrue(api.creates.isEmpty())

            vm.onCreateIbanEdited()
            assertNull(vm.state.createError)

            api.failWith = PosApiException(PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN, 409, "taken", null)
            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "DE89370400440532013000"))
            advanceUntilIdle()
            assertEquals(AdminStringKeys.BankErrorIbanTaken, vm.state.createError)
            assertEquals(2, vm.state.createAttempt, "a taken IBAN is the IBAN field's problem too")
        }

    @Test
    fun assigningWithoutAnAccount_flagsThePicker() {
        assertEquals(mapOf(AssignStatementField.ACCOUNT to CommonKeys.fieldRequired), validateAssignStatement(null))
        val account = BankAccountDto.ResponseDto(id = 1, iban = "DE89370400440532013000")
        assertTrue(validateAssignStatement(account).isEmpty())
    }
}
