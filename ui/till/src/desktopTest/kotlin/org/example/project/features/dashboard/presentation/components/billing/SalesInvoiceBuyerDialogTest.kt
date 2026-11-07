package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.example.project.core.data.remote.CustomerApiClient
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.utils.LocalVirtualKeyboard
import org.example.project.core.utils.VirtualKeyboardController
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * emu14 NOTE-4: in the till's *Rechnung erstellen* form the operator typed „Muster GmbH“ as the buyer
 * and tapped the next field; the name came out as the customer „Max Muster“. The floating suggestion
 * list lay over the street field, so the tap picked a customer. A suggestion is now applied only by
 * tapping it — moving on to another field keeps what was typed, and closes the list.
 */
@OptIn(ExperimentalTestApi::class)
class SalesInvoiceBuyerDialogTest {
    private val maxMuster =
        CustomerDto.CustomerSummaryDto(
            id = "c-1",
            customerNumber = "C001",
            fullName = "Max Muster",
            email = null,
            phone = null,
            isActive = true,
        )

    private val customers: CustomerApiClient =
        Proxy.newProxyInstance(CustomerApiClient::class.java.classLoader, arrayOf(CustomerApiClient::class.java)) { _, method, _ ->
            if (method.name != "searchCustomers") error("unexpected call ${method.name}")
            listOf(maxMuster)
        } as CustomerApiClient

    @BeforeTest
    fun setUp() {
        TranslationRegistry.initialize(defaultTranslations())
        startKoin { modules(module { single { customers } }) }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    private fun german(key: StringKey) = TranslationRegistry.get(key, Language.GERMAN)

    private fun field(key: StringKey) = hasSetTextAction() and hasText(german(key))

    @Test
    fun `moving on to the next field keeps the typed buyer name and closes the suggestions`() =
        runComposeUiTest {
            var sent: SalesInvoiceDto.CreateFromBillRequestDto? = null
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.GERMAN,
                    LocalVirtualKeyboard provides VirtualKeyboardController(),
                ) {
                    SalesInvoiceBuyerDialog(billId = 7L, onDismiss = {}, onConfirm = { sent = it })
                }
            }

            onNode(field(SalesInvoiceTillKeys.buyerName)).performTextInput("Muster GmbH")
            waitForIdle()
            onNodeWithText("Max Muster").assertExists("the customer search offers a suggestion")

            onNode(field(SalesInvoiceTillKeys.buyerStreet)).performClick()
            waitForIdle()

            onNodeWithText("Max Muster").assertDoesNotExist()
            onNode(field(SalesInvoiceTillKeys.buyerName) and hasText("Muster GmbH")).assertExists()
            onNodeWithText(german(SalesInvoiceTillKeys.confirmButton)).performClick()
            waitForIdle()
            assertEquals("Muster GmbH", sent?.buyerName)
        }

    @Test
    fun `tapping a suggestion still takes that customer's name`() =
        runComposeUiTest {
            var sent: SalesInvoiceDto.CreateFromBillRequestDto? = null
            setContent {
                CompositionLocalProvider(
                    LocalAppLanguage provides Language.GERMAN,
                    LocalVirtualKeyboard provides VirtualKeyboardController(),
                ) {
                    SalesInvoiceBuyerDialog(billId = 7L, onDismiss = {}, onConfirm = { sent = it })
                }
            }

            onNode(field(SalesInvoiceTillKeys.buyerName)).performTextInput("Max")
            waitForIdle()
            onNodeWithText("Max Muster").performClick()
            waitForIdle()

            onNodeWithText(german(SalesInvoiceTillKeys.confirmButton)).performClick()
            waitForIdle()
            assertEquals("Max Muster", sent?.buyerName)
        }
}
