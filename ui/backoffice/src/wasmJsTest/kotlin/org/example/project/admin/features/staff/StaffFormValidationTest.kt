package org.example.project.admin.features.staff

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.FakePermissionGroupApiClient
import org.example.project.admin.features.staff.presentation.CredentialsEditor
import org.example.project.admin.features.staff.presentation.CredentialsField
import org.example.project.admin.features.staff.presentation.CredentialsMode
import org.example.project.admin.features.staff.presentation.EmployeeEditor
import org.example.project.admin.features.staff.presentation.EmployeeField
import org.example.project.admin.features.staff.presentation.StaffViewModel
import org.example.project.admin.features.staff.presentation.validateCredentialsDraft
import org.example.project.admin.features.staff.presentation.validateEmployeeDraft
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Save is never disabled by the input in the employee editor and the credential reset: the click
 * validates, marks each refused field, bumps `validationAttempt` (the dialog's focus trigger) and
 * makes no request.
 */
class StaffFormValidationTest {
    @Test
    fun saveEditor_onAnEmptyCreate_marksBothRequiredFields_andMakesNoRequest() =
        runTest {
            val fake = FakeEmployeeApiClient()
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.openCreate()

            vm.saveEditor()

            val editor = assertNotNull(vm.editor)
            assertEquals(
                mapOf(EmployeeField.USERNAME to CommonKeys.fieldRequired, EmployeeField.PASSWORD to CommonKeys.fieldRequired),
                editor.fieldErrors,
            )
            assertEquals(1, editor.validationAttempt)
            assertNull(editor.fieldError, "nothing goes to the form-level line")
            assertNull(fake.created)
            assertFalse(vm.editorDirty, "a refused Save on a pristine form leaves it clean")

            vm.saveEditor()
            assertEquals(2, assertNotNull(vm.editor).validationAttempt, "every refused click re-reveals the first error")
        }

    @Test
    fun editingAField_clearsOnlyItsError_andAFixedFormSaves() =
        runTest {
            val fake = FakeEmployeeApiClient()
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.openCreate()
            vm.saveEditor()

            vm.onEditorField { it.copy(username = "carol") }
            assertEquals(setOf(EmployeeField.PASSWORD), assertNotNull(vm.editor).fieldErrors.keys)

            vm.onEditorField { it.copy(password = "secret1") }
            vm.saveEditor()
            assertEquals("carol", assertNotNull(fake.created).username)
        }

    @Test
    fun anEdit_needsNoUsernameOrPassword() {
        assertTrue(validateEmployeeDraft(EmployeeEditor(id = "1", username = "alice")).isEmpty())
    }

    @Test
    fun validationFlags_areNotDirt() {
        val opened = EmployeeEditor()
        val refused = opened.copy(fieldErrors = mapOf(EmployeeField.USERNAME to CommonKeys.fieldRequired), validationAttempt = 4)
        assertFalse(refused.isDirtyFrom(opened))
    }

    @Test
    fun saveCredentials_onAnEmptyForm_marksAllThreeFields_andDoesNotReauth() =
        runTest {
            val fake = FakeEmployeeApiClient(summaries = listOf(summary("7", "dan", "CASHIER")))
            val vm = StaffViewModel(fake, FakePermissionGroupApiClient())
            vm.refresh()
            vm.openResetPassword("7")

            vm.saveCredentials()

            val editor = assertNotNull(vm.credentialsEditor)
            assertEquals(CredentialsField.entries.toSet(), editor.fieldErrors.keys)
            assertEquals(1, editor.validationAttempt)
            assertNull(fake.authenticatedWith)
            assertFalse(editor.isDirty, "the leave guard ignores validation flags")

            vm.onCredentialField { it.copy(newValue = "secret1", confirmValue = "secret1") }
            assertEquals(setOf(CredentialsField.MANAGER_PASSWORD), assertNotNull(vm.credentialsEditor).fieldErrors.keys)
        }

    @Test
    fun aPinMustBeFourToSixDigits() {
        val pin = CredentialsEditor(employeeId = "7", mode = CredentialsMode.PIN, newValue = "123", confirmValue = "123", managerPassword = "x")
        assertEquals(mapOf(CredentialsField.NEW_VALUE to AdminStringKeys.StaffCredErrorInvalid), validateCredentialsDraft(pin))
        assertTrue(validateCredentialsDraft(pin.copy(newValue = "1234", confirmValue = "1234")).isEmpty())
    }
}
