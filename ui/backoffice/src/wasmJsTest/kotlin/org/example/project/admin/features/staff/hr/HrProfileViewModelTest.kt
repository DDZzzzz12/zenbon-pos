package org.example.project.admin.features.staff.hr

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.staff.FakeEmployeeApiClient
import org.example.project.admin.features.staff.employeeInfoResponse
import org.example.project.admin.features.staff.presentation.hr.HrEditorError
import org.example.project.admin.features.staff.presentation.hr.HrField
import org.example.project.admin.features.staff.presentation.hr.HrProfileState
import org.example.project.admin.features.staff.presentation.hr.HrProfileViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun vm(employees: FakeEmployeeApiClient = FakeEmployeeApiClient()) = HrProfileViewModel(employees)

class HrProfileViewModelTest {
    @Test
    fun load_populatesHrRecord() =
        runTest {
            val employees = FakeEmployeeApiClient()
            employees.info = employeeInfoResponse("7")
            val vm = vm(employees)

            vm.load("7")

            val data = assertIs<HrProfileState.Data>(vm.state)
            assertEquals("7", data.employeeId)
            val info = assertNotNull(data.info)
            assertEquals("Ada", info.firstName)
            assertEquals("18.50", info.hourlyRate)
        }

    @Test
    fun load_withNoHrRecord_yieldsDataWithNullInfo() =
        runTest {
            val employees = FakeEmployeeApiClient() // info stays null → the fake throws 404
            val vm = vm(employees)

            vm.load("7")

            val data = assertIs<HrProfileState.Data>(vm.state)
            assertNull(data.info)
        }

    @Test
    fun openEditor_seedsFromLoadedRecord() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")

            vm.openEditor()

            val editor = assertNotNull(vm.editor)
            assertEquals("Ada", editor.firstName)
            assertEquals("18.50", editor.hourlyRate)
            assertEquals(false, editor.isCreate)
        }

    @Test
    fun openEditor_withNoHrRecord_isCreate() =
        runTest {
            val vm = vm()
            vm.load("7")

            vm.openEditor()

            assertEquals(true, assertNotNull(vm.editor).isCreate)
        }

    @Test
    fun closeEditor_clearsDraft() =
        runTest {
            val vm = vm()
            vm.load("7")
            vm.openEditor()

            vm.closeEditor()

            assertNull(vm.editor)
        }

    @Test
    fun save_rejectsBlankName() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(firstName = "  ") }

            vm.save()

            val editor = assertNotNull(vm.editor)
            assertEquals(setOf(HrField.FIRST_NAME), editor.fieldErrors.keys, "the error sits on the field, not in a top banner")
            assertEquals(1, editor.validationAttempt)
            assertNull(editor.error)
            assertNull(employees.updatedInfo)

            vm.onEditorField { it.copy(firstName = "Ana") }
            assertEquals(emptyMap(), assertNotNull(vm.editor).fieldErrors, "typing into the flagged field clears it")
        }

    @Test
    fun save_rejectsMalformedEmail() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(email = "not-an-email") }

            vm.save()

            assertEquals(setOf(HrField.EMAIL), assertNotNull(vm.editor).fieldErrors.keys)
            assertNull(employees.updatedInfo)
        }

    @Test
    fun save_rejectsUnparseableAmount() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(hourlyRate = "18,5,0") }

            vm.save()

            assertEquals(setOf(HrField.HOURLY_RATE), assertNotNull(vm.editor).fieldErrors.keys)
            assertNull(employees.updatedInfo)
        }

    @Test
    fun save_rejectsADateFieldHoldingTextThatIsNotADate() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            // What the date field hands over for "31.02.1990": the typed text, since it is no date.
            vm.onEditorField { it.copy(dateOfBirth = "31.02.1990") }

            vm.save()

            assertEquals(setOf(HrField.DATE_OF_BIRTH), assertNotNull(vm.editor).fieldErrors.keys)
            assertNull(employees.updatedInfo)
        }

    @Test
    fun save_canonicalisesCommaDecimals() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(hourlyRate = "19,25", salary = "3400,00", contractedHours = "38,5") }

            vm.save()

            val sent = assertNotNull(employees.updatedInfo)
            assertEquals("19.25", sent.hourlyRate)
            assertEquals("3400.00", sent.salary)
            assertEquals("38.5", sent.contractedHours)
        }

    @Test
    fun save_clearedField_isSentAsEmptyString_soTheServerErasesIt() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(phoneNumber = "", salary = " ", hireDate = "") }

            vm.save()

            val sent = assertNotNull(employees.updatedInfo)
            assertEquals("", sent.phoneNumber)
            assertEquals("", sent.salary)
            assertEquals("", sent.hireDate)
            assertEquals("Berlin", sent.city, "an untouched field is re-sent as is")
        }

    @Test
    fun save_fieldThatWasAlreadyEmpty_isSentAsNull_notAsAClear() =
        runTest {
            val employees =
                FakeEmployeeApiClient().apply {
                    info = employeeInfoResponse("7").copy(phoneNumber = null, salary = null, jobTitle = null)
                }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(city = "Hamburg") }

            vm.save()

            val sent = assertNotNull(employees.updatedInfo)
            assertNull(sent.phoneNumber)
            assertNull(sent.salary)
            assertNull(sent.jobTitle)
            assertEquals("Hamburg", sent.city)
        }

    @Test
    fun save_blankName_isStillRefused_namesCannotBeCleared() =
        runTest {
            val employees = FakeEmployeeApiClient().apply { info = employeeInfoResponse("7") }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(lastName = "") }

            vm.save()

            assertEquals(setOf(HrField.LAST_NAME), assertNotNull(vm.editor).fieldErrors.keys)
            assertNull(employees.updatedInfo)
        }

    @Test
    fun save_hrLegFailure_keepsDialogOpenAndWritesNothingElse() =
        runTest {
            val employees =
                FakeEmployeeApiClient().apply {
                    info = employeeInfoResponse("7")
                    infoWriteShouldFail = true
                }
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(jobTitle = "Schichtleitung") }

            vm.save()

            val editor = assertNotNull(vm.editor)
            assertEquals(HrEditorError.SAVE_FAILED, editor.error)
            assertNull(employees.updatedId) // the HR save must not touch the employee endpoint
        }

    @Test
    fun save_withNoHrRecord_createsInsteadOfUpdating() =
        runTest {
            val employees = FakeEmployeeApiClient()
            val vm = vm(employees)
            vm.load("7")
            vm.openEditor()
            vm.onEditorField { it.copy(firstName = "Grace", lastName = "Hopper") }

            vm.save()

            val created = assertNotNull(employees.createdInfo)
            assertEquals("7", created.employeeId)
            assertEquals("Grace", created.firstName)
            assertNull(employees.updatedInfoId)
        }
}
