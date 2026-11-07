package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.features.reports.presentation.components.dailyreport.BillTransactionRow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The related-bill chips of a Berichte list row and the tappable links of the receipt panel, as the
 * operator sees them (German).
 */
@OptIn(ExperimentalTestApi::class)
class BillRelationRenderingTest {
    @BeforeTest
    fun translations() = TranslationRegistry.initialize(defaultTranslations())

    private fun link(
        id: Long,
        number: String,
    ) = BillDto.BillLinkDto(id, number)

    private fun row(
        number: String,
        amount: String,
        payments: List<Pair<String, String>>,
        relations: BillDto.BillRelationsDto,
    ) = BillDto.BillSummaryDto(
        billId = 1,
        billNumber = number,
        billAmount = amount,
        paidAmount = amount,
        paymentType = payments.map { (type, value) -> PaymentDto.PaymentSummary(amount = value, paymentTypeDE = type) },
        tips = "0.00",
        endTime = "2026-09-27T12:00:00",
        employeeNumber = "07",
        relations = relations,
    )

    private fun detail(relations: BillDto.BillRelationsDto) =
        BillDto.BillResponseDto(
            billId = 5,
            employeeId = "emp-1",
            employeeNumber = "07",
            tipRecipient = "EMPLOYEE",
            tableSessionId = 500L,
            tableNumber = "12",
            billNumber = "RE-5",
            billAmount = "0.00",
            paidAmount = "0.00",
            tips = "0.00",
            startTime = "2026-09-27T12:00:00",
            endTime = "2026-09-27T12:00:00",
            orderItems = emptyList(),
            payments = emptyList(),
            relations = relations,
        )

    private fun SemanticsNodeInteractionCollection.texts(): List<String> =
        fetchSemanticsNodes().flatMap { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        }

    private val stornoRow =
        row("RE-6", "-10.60", listOf("EC-KARTE" to "10.60"), BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(4, "RE-4")))
    private val cancelledOriginal =
        row(
            "RE-4",
            "10.60",
            listOf("EC-KARTE" to "10.60"),
            BillDto.BillRelationsDto(isCancelled = true, reversedBy = link(6, "RE-6")),
        )

    @Test
    fun `list rows carry their relation chips, compact and wide`() {
        listOf(true, false).forEach { compact ->
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                        Column {
                            BillTransactionRow(bill = stornoRow, isCompact = compact, onClick = {})
                            BillTransactionRow(bill = cancelledOriginal, isCompact = compact, onClick = {})
                        }
                    }
                }
                val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).texts()
                listOf("Storno zu RE-4", "Storniert")
                    .forEach { assertTrue(it in texts, "compact=$compact: expected chip \"$it\" in $texts") }
            }
        }
    }

    @Test
    fun `a re-bill row reads as a Neubuchung, partial and with several originals`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Column {
                        BillTransactionRow(
                            bill = row("RE-8", "23.00", listOf("BAR" to "23.00"), BillDto.BillRelationsDto(rebilledFromBills = listOf(link(4, "RE-4")))),
                            isCompact = false,
                            onClick = {},
                        )
                        BillTransactionRow(
                            bill =
                                row(
                                    "RE-9",
                                    "30.00",
                                    listOf("BAR" to "30.00"),
                                    BillDto.BillRelationsDto(rebilledFromBills = listOf(link(4, "RE-4"), link(7, "RE-7")), rebillIsPartial = true),
                                ),
                            isCompact = false,
                            onClick = {},
                        )
                    }
                }
            }
            val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).texts()
            listOf("Neubuchung zu RE-4", "Neubuchung zu RE-4, RE-7 (teilweise)")
                .forEach { assertTrue(it in texts, "expected chip \"$it\" in $texts") }
        }

    @Test
    fun `the detail panel's links open the linked bill and back returns`() =
        runComposeUiTest {
            val opened = mutableListOf<BillDto.BillLinkDto>()
            var backs = 0
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Box(Modifier.height(2000.dp)) {
                        PosBillDetailPanel(
                            bill =
                                detail(
                                    BillDto.BillRelationsDto(
                                        isCancelled = true,
                                        reversedBy = link(9, "RE-9"),
                                    ),
                                ),
                            tableNumber = "12",
                            onOpenLinkedBill = { opened += it },
                            backTo = link(3, "RE-3"),
                            onBack = { backs++ },
                        )
                    }
                }
            }
            onNodeWithText("Storniert durch RE-9").performClick()
            assertEquals(listOf(link(9, "RE-9")), opened)
            onNodeWithText("Storniert").assertExists()

            onNodeWithText("Zurück zu RE-3").performClick()
            assertEquals(1, backs)
        }

    @Test
    fun `a Storno's detail names its original`() =
        runComposeUiTest {
            val opened = mutableListOf<BillDto.BillLinkDto>()
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Column {
                        Box(Modifier.height(900.dp)) {
                            PosBillDetailPanel(
                                bill = detail(BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(4, "RE-4"))),
                                tableNumber = "12",
                                onOpenLinkedBill = { opened += it },
                            )
                        }
                        Box(Modifier.height(900.dp)) {
                            // No navigation host: the link still names the original, as plain text.
                            PosBillDetailPanel(
                                bill = detail(BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(4, "RE-4"))),
                                tableNumber = "12",
                            )
                        }
                    }
                }
            }
            onAllNodesWithText("Storno zu RE-4")[0].performClick()
            assertEquals(listOf(link(4, "RE-4")), opened)
            assertEquals(2, onAllNodesWithText("Storno zu RE-4").fetchSemanticsNodes().size)
            onNodeWithText("Zurück zu", substring = true).assertDoesNotExist()
        }

    @Test
    fun `a Zahlartwechsel family carries its chips on all three rows`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Column {
                        BillTransactionRow(
                            bill =
                                row(
                                    "RE-4",
                                    "23.00",
                                    listOf("BAR" to "23.00"),
                                    BillDto.BillRelationsDto(isCancelled = true, reversedBy = link(5, "RE-5"), rebookedBy = link(6, "RE-6")),
                                ),
                            isCompact = false,
                            onClick = {},
                        )
                        BillTransactionRow(
                            bill =
                                row(
                                    "RE-5",
                                    "-23.00",
                                    listOf("BAR" to "-23.00"),
                                    BillDto.BillRelationsDto(
                                        kind = BillKind.STORNO,
                                        reversesBill = link(4, "RE-4"),
                                        stornoReason = StornoReason.ZAHLARTWECHSEL,
                                    ),
                                ),
                            isCompact = false,
                            onClick = {},
                        )
                        BillTransactionRow(
                            bill =
                                row(
                                    "RE-6",
                                    "23.00",
                                    listOf("EC-KARTE" to "23.00"),
                                    BillDto.BillRelationsDto(kind = BillKind.REBOOKING, rebooksBill = link(4, "RE-4")),
                                ),
                            isCompact = false,
                            onClick = {},
                        )
                    }
                }
            }
            val texts = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).texts()
            listOf("Storniert (Zahlartwechsel)", "Storno (Zahlartwechsel) zu RE-4", "Neubuchung zu RE-4")
                .forEach { assertTrue(it in texts, "expected chip \"$it\" in $texts") }
            assertTrue("Storniert" !in texts, "a rebooked original is not shown as a plain (refund) Storno")
            assertTrue("Storno zu RE-4" !in texts, "nor is the Zahlartwechsel Storno")
        }

    @Test
    fun `a bill opened to look at it is headed Beleg, only the created-bill confirmation says Rechnung erstellt`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Column {
                        Box(Modifier.height(900.dp)) {
                            PosBillDetailPanel(
                                bill = detail(BillDto.BillRelationsDto(kind = BillKind.STORNO, reversesBill = link(4, "RE-4"))),
                                tableNumber = "12",
                            )
                        }
                        Box(Modifier.height(900.dp)) {
                            PosBillDetailPanel(bill = detail(BillDto.BillRelationsDto()), tableNumber = "12", showSuccessHeader = true)
                        }
                    }
                }
            }
            assertEquals(1, onAllNodesWithText("Beleg").fetchSemanticsNodes().size, "the Storno viewed from Berichte")
            assertEquals(1, onAllNodesWithText("Rechnung erstellt").fetchSemanticsNodes().size, "only the confirmation")
        }

    @Test
    fun `a rebooked original links its Storno and its Neubuchung`() =
        runComposeUiTest {
            val opened = mutableListOf<BillDto.BillLinkDto>()
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides Language.GERMAN) {
                    Box(Modifier.height(2000.dp)) {
                        PosBillDetailPanel(
                            bill =
                                detail(
                                    BillDto.BillRelationsDto(
                                        isCancelled = true,
                                        reversedBy = link(5, "RE-5"),
                                        rebookedBy = link(6, "RE-6"),
                                    ),
                                ),
                            tableNumber = "12",
                            onOpenLinkedBill = { opened += it },
                        )
                    }
                }
            }
            onNodeWithText("Storniert (Zahlartwechsel)").assertExists()
            onNodeWithText("Storniert durch RE-5").assertExists()
            onNodeWithText("Neu gebucht als RE-6").performClick()
            assertEquals(listOf(link(6, "RE-6")), opened)
        }
}
