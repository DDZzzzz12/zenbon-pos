package org.example.project.admin.features.debtors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.debtors.presentation.DebtorDetailViewModel
import org.example.project.admin.features.debtors.presentation.DebtorFormField
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NEW-20: the Debitor e-mail is checked on both levels. The form refuses a malformed address on Save
 * (validate-on-press, the field is marked and nothing is sent); a 400 carrying an `email` field error
 * from the server (a client that skipped the check) lands on the same field instead of a dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorEmailValidationTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a malformed e-mail marks the field and sends nothing`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)
            vm.load(null)
            vm.onNameChange("Acme GmbH")
            vm.onEmailChange("kaputt")

            vm.save()
            advanceUntilIdle()

            assertEquals(mapOf(DebtorFormField.EMAIL to AdminStringKeys.PartnerEmailInvalid), vm.state.fieldErrors)
            assertEquals(1, vm.state.validationAttempt)
            assertNull(api.created, "a refused form never reaches the server")
        }

    @Test
    fun `editing the e-mail clears its error and a blank e-mail saves`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            val vm = DebtorDetailViewModel(api)
            vm.load(null)
            vm.onNameChange("Acme GmbH")
            vm.onEmailChange("kaputt")
            vm.save()
            advanceUntilIdle()

            vm.onEmailChange("")
            assertEquals(emptyMap(), vm.state.fieldErrors)
            vm.save()
            advanceUntilIdle()

            assertEquals("Acme GmbH", api.created?.name)
        }

    @Test
    fun `the server's email field error is shown on the field, not as a dialog`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient()
            api.saveThrows =
                PosApiException(
                    code = PosErrorCode.VALIDATION_FAILED,
                    httpStatus = 400,
                    message = "Invalid or missing: email",
                    traceId = null,
                    fieldErrors = listOf(FieldError(field = "email", code = "INVALID_FORMAT")),
                )
            val vm = DebtorDetailViewModel(api)
            vm.load(null)
            vm.onNameChange("Acme GmbH")
            vm.onEmailChange("info@acme.de")

            vm.save()
            advanceUntilIdle()

            assertEquals(mapOf(DebtorFormField.EMAIL to AdminStringKeys.PartnerEmailInvalid), vm.state.fieldErrors)
            assertNull(vm.state.error)
            assertEquals(false, vm.state.isSaving)
        }
}
