package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bill panel (till history/receipt overlay and adminApp's Z-Bon/Tagesbericht panel share it)
 * shows each chosen option with its price delta — QA admin21 / emu21 NOTE-y: "+ Groß, Extra Käse"
 * with no prices next to a 20,00 € line — in the SCREEN money format, not the print label's
 * „Groß  (2.50)“ (QA emu22 NOTE-gg).
 */
@OptIn(ExperimentalTestApi::class)
class BillPanelOptionDeltaTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun option(
        name: String,
        delta: String,
    ) = OrderItemDto.SelectedOptionDto(optionId = 1, name = name, priceDelta = delta, groupName = "Größe", groupId = 1)

    private val bill =
        BillDto.BillResponseDto(
            billId = 17,
            employeeId = "emp-1",
            employeeNumber = "07",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 500L,
            tableNumber = "12",
            billNumber = "RE-17",
            billAmount = "11.00",
            paidAmount = "11.00",
            tips = "0.00",
            startTime = "2026-10-03T12:00:00",
            endTime = "2026-10-03T12:00:00",
            orderItems =
                listOf(
                    OrderItemDto.OrderItemResponseDto(
                        orderItemIds = listOf(1L),
                        ordersId = setOf(1L),
                        product = ProductDto.OrderItemProductDto(1L, "N1", "Nudelbox", productType = "FOOD"),
                        unitPrice = "8.0000",
                        finalPrice = "11.00",
                        itemStatus = "PAID",
                        vatClass = "A",
                        vatRate = "19.00",
                        customPrice = false,
                        discount = 1.0,
                        takeAway = false,
                        transferred = false,
                        quantity = 1,
                        extras = listOf(option("Groß", "2.5000"), option("Ohne Zwiebeln", "0.0000")),
                    ),
                ),
            payments = emptyList(),
        )

    @Test
    fun `options carry their delta in the screen money format, a zero delta none`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Box(Modifier.height(2000.dp)) { PosBillDetailPanel(bill = bill, tableNumber = "12") }
                }
            }
            val texts =
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { node ->
                        node.config
                            .getOrNull(SemanticsProperties.Text)
                            .orEmpty()
                            .map { it.text }
                    }
            assertTrue(
                texts.any { "Groß (+2,50 €)" in it && "Ohne Zwiebeln" in it && "Ohne Zwiebeln (" !in it },
                "expected the screen label of each option in $texts",
            )
            assertTrue(texts.none { "(2.50)" in it || "  (" in it }, "the print spelling leaked onto the screen: $texts")
        }
}
