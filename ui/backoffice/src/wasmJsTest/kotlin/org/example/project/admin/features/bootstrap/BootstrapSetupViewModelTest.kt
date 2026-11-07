package org.example.project.admin.features.bootstrap

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.bootstrap.presentation.BootstrapField
import org.example.project.admin.features.bootstrap.presentation.BootstrapSetupViewModel
import org.example.project.admin.features.bootstrap.presentation.BootstrapStatus
import org.example.project.admin.features.bootstrap.presentation.BootstrapStep
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapSetupViewModelTest {
    private fun vm(setup: FakeSetupApiClient = FakeSetupApiClient()) = BootstrapSetupViewModel(setup)

    @Test
    fun probe_notInitialized_setsNeedsBootstrap() =
        runTest {
            val setup =
                FakeSetupApiClient().apply {
                    statusResult = InitialSetupDto.InitialSetupStatusDto(isInitialized = false, requiresSetup = true)
                }
            val viewModel = vm(setup)

            viewModel.evaluateBootstrap()

            assertEquals(BootstrapStatus.NEEDS_BOOTSTRAP, viewModel.state.status)
        }

    @Test
    fun probe_initialized_setsInitialized() =
        runTest {
            val setup =
                FakeSetupApiClient().apply {
                    statusResult = InitialSetupDto.InitialSetupStatusDto(isInitialized = true, requiresSetup = false)
                }
            val viewModel = vm(setup)

            viewModel.evaluateBootstrap()

            assertEquals(BootstrapStatus.INITIALIZED, viewModel.state.status)
        }

    @Test
    fun probe_error_defaultsToInitialized_soLoginIsShown() =
        runTest {
            val setup =
                FakeSetupApiClient().apply {
                    statusError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(setup)

            viewModel.evaluateBootstrap()

            // Fail safe: a probe failure must not trap the user on a bootstrap wizard for an
            // already-initialized server; fall through to login (matches composeApp requiresSetup=false).
            assertEquals(BootstrapStatus.INITIALIZED, viewModel.state.status)
        }

    @Test
    fun probe_runsOnce() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)

            viewModel.evaluateBootstrap()
            viewModel.evaluateBootstrap()

            assertEquals(1, setup.statusCallCount)
        }

    @Test
    fun nextStep_invalidOwner_staysOnOwner_setsValidationErrors() =
        runTest {
            val viewModel = vm()
            // all owner fields blank
            viewModel.nextStep()

            assertEquals(BootstrapStep.OWNER, viewModel.state.step)
            assertTrue(viewModel.state.validationErrors.containsKey(BootstrapField.ADMIN_USERNAME))
            assertTrue(viewModel.state.validationErrors.containsKey(BootstrapField.ADMIN_PIN))
        }

    @Test
    fun nextStep_validOwner_advancesToShop_andClearsErrors() =
        runTest {
            val viewModel = vm()
            viewModel.updateField(BootstrapField.ADMIN_USERNAME, "owner")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD_CONFIRM, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_EMAIL, "o@example.com")
            viewModel.updateField(BootstrapField.ADMIN_FIRST_NAME, "Ada")
            viewModel.updateField(BootstrapField.ADMIN_LAST_NAME, "Lovelace")
            viewModel.updateField(BootstrapField.ADMIN_PIN, "1234")

            viewModel.nextStep()

            assertEquals(BootstrapStep.SHOP, viewModel.state.step)
            assertTrue(viewModel.state.validationErrors.isEmpty())
        }

    /**
     * The server writes both names to `employee_info`, where DSFinV-K's BEDIENER_NAME comes from,
     * and refuses a blank one. Caught on step 1 so it is a field error, not a 400 after step 2.
     */
    @Test
    fun nextStep_blankName_staysOnOwner() =
        runTest {
            val viewModel = vm()
            viewModel.updateField(BootstrapField.ADMIN_USERNAME, "owner")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD_CONFIRM, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_EMAIL, "o@example.com")
            viewModel.updateField(BootstrapField.ADMIN_LAST_NAME, "Lovelace")
            viewModel.updateField(BootstrapField.ADMIN_PIN, "1234")

            viewModel.nextStep()

            assertEquals(BootstrapStep.OWNER, viewModel.state.step)
            assertTrue(viewModel.state.validationErrors.containsKey(BootstrapField.ADMIN_FIRST_NAME))
        }

    @Test
    fun nextStep_passwordMismatch_flagsConfirmField() =
        runTest {
            val viewModel = vm()
            viewModel.updateField(BootstrapField.ADMIN_USERNAME, "owner")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD_CONFIRM, "secret2")
            viewModel.updateField(BootstrapField.ADMIN_EMAIL, "o@example.com")
            viewModel.updateField(BootstrapField.ADMIN_FIRST_NAME, "Ada")
            viewModel.updateField(BootstrapField.ADMIN_LAST_NAME, "Lovelace")
            viewModel.updateField(BootstrapField.ADMIN_PIN, "1234")

            viewModel.nextStep()

            assertEquals(BootstrapStep.OWNER, viewModel.state.step)
            assertTrue(viewModel.state.validationErrors.containsKey(BootstrapField.ADMIN_PASSWORD_CONFIRM))
        }

    private fun BootstrapSetupViewModel.fillValid() {
        updateField(BootstrapField.ADMIN_USERNAME, "owner")
        updateField(BootstrapField.ADMIN_PASSWORD, "secret1")
        updateField(BootstrapField.ADMIN_PASSWORD_CONFIRM, "secret1")
        updateField(BootstrapField.ADMIN_EMAIL, "o@example.com")
        updateField(BootstrapField.ADMIN_FIRST_NAME, "Ada")
        updateField(BootstrapField.ADMIN_LAST_NAME, "Lovelace")
        updateField(BootstrapField.ADMIN_PIN, "1234")
        nextStep()
        updateField(BootstrapField.SHOP_NAME, "Cafe")
        updateField(BootstrapField.SHOP_ADDRESS, "Main 1")
        updateField(BootstrapField.SHOP_CITY, "Berlin")
        updateField(BootstrapField.ZIP_CODE, "10115")
        updateField(BootstrapField.SHOP_PHONE, "030-1")
    }

    @Test
    fun submit_invalidShop_doesNotCallInitialize() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            // jump to SHOP with a valid owner but leave shop blank
            viewModel.updateField(BootstrapField.ADMIN_USERNAME, "owner")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_PASSWORD_CONFIRM, "secret1")
            viewModel.updateField(BootstrapField.ADMIN_EMAIL, "o@example.com")
            viewModel.updateField(BootstrapField.ADMIN_FIRST_NAME, "Ada")
            viewModel.updateField(BootstrapField.ADMIN_LAST_NAME, "Lovelace")
            viewModel.updateField(BootstrapField.ADMIN_PIN, "1234")
            viewModel.nextStep()

            viewModel.submitBootstrap()

            assertEquals(null, setup.lastSubmitRequest)
            assertTrue(viewModel.state.validationErrors.containsKey(BootstrapField.SHOP_NAME))
        }

    @Test
    fun submit_valid_callsInitialize_flipsToInitialized_capturesRequest() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            viewModel.fillValid()

            viewModel.submitBootstrap()

            assertEquals(BootstrapStatus.INITIALIZED, viewModel.state.status)
            assertTrue(viewModel.state.isComplete)
            assertEquals("owner", setup.lastSubmitRequest?.owner?.adminUsername)
            assertEquals("Cafe", setup.lastSubmitRequest?.shop?.shopName)
            assertEquals(10115, setup.lastSubmitRequest?.shop?.zipCode)
        }

    @Test
    fun submit_failure_surfacesTypedError_staysNotInitialized() =
        runTest {
            val setup =
                FakeSetupApiClient().apply {
                    submitError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(setup)
            viewModel.fillValid()

            viewModel.submitBootstrap()

            assertTrue(viewModel.state.error is UiError.Network)
            assertEquals(false, viewModel.state.isComplete)
        }

    @Test
    fun restore_success_flipsToInitialized() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK" — a ZIP magic header

            viewModel.restoreFromBackup(bytes)

            assertEquals(BootstrapStatus.INITIALIZED, viewModel.state.status)
            assertFalse(viewModel.state.isRestoring)
            assertEquals(1, setup.restoreCallCount)
        }

    @Test
    fun restore_sendsExactBytes() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            val bytes = byteArrayOf(1, 2, 3, 4, 5)

            viewModel.restoreFromBackup(bytes)

            assertTrue(bytes.contentEquals(setup.lastRestoreBytes))
        }

    @Test
    fun restore_failure_surfacesTypedError_staysOnWizard() =
        runTest {
            val setup =
                FakeSetupApiClient().apply {
                    restoreError = { throw NetworkApiException(RuntimeException("offline")) }
                }
            val viewModel = vm(setup)
            viewModel.evaluateBootstrap() // the real precondition: wizard is showing

            viewModel.restoreFromBackup(byteArrayOf(1))

            assertTrue(viewModel.state.error is UiError.Network)
            assertFalse(viewModel.state.isRestoring)
            // A failed restore must not unlock the app: the gate has to keep showing the wizard.
            assertEquals(BootstrapStatus.NEEDS_BOOTSTRAP, viewModel.state.status)
        }

    @Test
    fun submit_sendsEveryOptionalShopField_trimmed() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            viewModel.fillValid()
            viewModel.updateField(BootstrapField.SHOP_TAX_NUMBER, "  12/345/67890 ")
            viewModel.updateField(BootstrapField.UST_ID_NR, " de123456789 ")
            viewModel.updateField(BootstrapField.SHOP_BANK_NAME, " Sparkasse ")
            viewModel.updateField(BootstrapField.SHOP_BANK_BIC, " BYLADEM1001 ")
            viewModel.updateField(BootstrapField.SHOP_BANK_IBAN, " DE02120300000000202051 ")
            viewModel.updateField(BootstrapField.SHOP_WEBSITE, " https://cafe.example ")
            viewModel.updateField(BootstrapField.SHOP_TIMEZONE, "Europe/Berlin")
            viewModel.updateField(BootstrapField.SHOP_COUNTRY, "Germany")
            viewModel.updateField(BootstrapField.CURRENCY_CODE, "EUR")

            viewModel.submitBootstrap()

            val shop = setup.lastSubmitRequest?.shop
            assertEquals("12/345/67890", shop?.shopTaxNumber)
            assertEquals("DE123456789", shop?.ustIdNr)
            assertEquals("Sparkasse", shop?.shopBankName)
            assertEquals("BYLADEM1001", shop?.shopBankBic)
            assertEquals("DE02120300000000202051", shop?.shopBankIBAN)
            assertEquals("https://cafe.example", shop?.shopWebsite)
            assertEquals("Europe/Berlin", shop?.shopTimezone)
        }

    @Test
    fun submit_blankOptionalShopFields_sendNull_notEmptyString() =
        runTest {
            val setup = FakeSetupApiClient()
            val viewModel = vm(setup)
            viewModel.fillValid() // leaves every optional field blank

            viewModel.submitBootstrap()

            val shop = setup.lastSubmitRequest?.shop
            // A blank must not reach the DB as "" — DSFinV-K distinguishes absent from empty.
            assertEquals(null, shop?.shopTaxNumber)
            assertEquals(null, shop?.ustIdNr)
            assertEquals(null, shop?.shopBankName)
            assertEquals(null, shop?.shopBankBic)
            assertEquals(null, shop?.shopBankIBAN)
            assertEquals(null, shop?.shopWebsite)
        }
}
