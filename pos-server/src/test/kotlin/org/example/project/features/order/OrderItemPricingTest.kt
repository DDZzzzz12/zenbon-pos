package org.example.project.features.order

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.database.tables.ItemExtrasTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.mapper.toOrderItemResponseDto
import org.example.project.features.product.data.repository.ChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductExtrasRepositoryImpl
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.domain.models.ChoiceGroup
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository-backed test for the choice-group pricing/validation data path used by
 * [org.example.project.features.order.data.service.OrderServiceImpl.resolveSelections].
 *
 * LIMITATION: `OrderServiceImpl.createOrder` requires the full service graph (TSE factory,
 * printer service, terminal repo, shop-info service, config manager, …) which is impractical
 * to assemble in a focused unit test. Since `resolveSelections` is a private method, this test
 * exercises the exact pieces it relies on against the real repositories + a real product:
 *   - the authoritative per-placement delta read from `choice_group_option`
 *   - the `finalPrice = (unitPrice + Σ(delta × qty)) × discount` formula
 *   - the min/max-per-group validation rule (rejection of an empty required group)
 * An end-to-end createOrder assertion is left for the later integration gate.
 */
class OrderItemPricingTest {
    private lateinit var choiceGroupRepo: ChoiceGroupRepositoryImpl
    private lateinit var productChoiceGroupRepo: ProductChoiceGroupRepositoryImpl
    private lateinit var extrasRepo: ProductExtrasRepositoryImpl

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        choiceGroupRepo = ChoiceGroupRepositoryImpl()
        productChoiceGroupRepo = ProductChoiceGroupRepositoryImpl()
        extrasRepo = ProductExtrasRepositoryImpl()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `selection delta is added to final price`() =
        runBlocking {
            val category = TestData.createCategory(name = "Mains")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "Schnitzel",
                    productNumber = "SCH001",
                    price = BigDecimal("18.9000"),
                )

            // A required pick-one group "Protein" with a Salmon option that costs +3.00 in this placement.
            val (groupId, salmonId) =
                dbQuery {
                    val group = choiceGroupRepo.create("Protein", null, null, minSelections = 1, maxSelections = 1, isActive = true)
                    val salmon = extrasRepo.createExtra("Salmon", null, null, BigDecimal("0.0000"), true)
                    choiceGroupRepo.replaceOptions(
                        group.id.value,
                        listOf(ChoiceGroupRepository.OptionRow(salmon.id.value, BigDecimal("3.0000"), 0, false)),
                    )
                    productChoiceGroupRepo.replaceForProduct(product.id.value, listOf(group.id.value))
                    group.id.value to salmon.id.value
                }

            // Resolve the authoritative delta exactly as resolveSelections does.
            val delta =
                dbQuery {
                    choiceGroupRepo
                        .getOptions(groupId)
                        .first { it[ChoiceGroupOptionTable.optionId].value == salmonId }[ChoiceGroupOptionTable.priceDelta]
                }
            assertEquals(BigDecimal("3.0000"), delta)

            val unitPrice = BigDecimal("18.9000")
            val discount = BigDecimal.ONE
            val finalPrice = (unitPrice + delta.multiply(BigDecimal(1))) * discount

            // 18.90 + 3.00 = 21.90 (formatDecimal renders 4-scale)
            assertEquals(0, BigDecimal("21.9000").compareTo(finalPrice.setScale(4)))
            Unit
        }

    @Test
    fun `a captured option snapshots its group id, so it can be re-priced on re-submit`() =
        runBlocking {
            val category = TestData.createCategory(name = "Mains3")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "Burger",
                    productNumber = "BRG001",
                    price = BigDecimal("10.0000"),
                )
            val vat = TestData.createDefaultVatRates().first()
            val session = TestData.createTableWithSession(tableNumber = "T-SNAP").session

            // Cheese sits in TWO groups at different deltas — the case that makes the group id
            // load-bearing. Its own product_extras.price is a third, unrelated number, so anything
            // resolving by option id alone lands on the wrong amount.
            val (toppingsId, cheeseId) =
                dbQuery {
                    val cheese = extrasRepo.createExtra("Cheese", null, null, BigDecimal("1.0000"), true)
                    val toppings = choiceGroupRepo.create("Toppings", null, null, 0, 3, true)
                    val premium = choiceGroupRepo.create("Premium", null, null, 0, 3, true)
                    choiceGroupRepo.replaceOptions(
                        toppings.id.value,
                        listOf(ChoiceGroupRepository.OptionRow(cheese.id.value, BigDecimal("0.5000"), 0, false)),
                    )
                    choiceGroupRepo.replaceOptions(
                        premium.id.value,
                        listOf(ChoiceGroupRepository.OptionRow(cheese.id.value, BigDecimal("2.0000"), 0, false)),
                    )
                    productChoiceGroupRepo.replaceForProduct(product.id.value, listOf(toppings.id.value, premium.id.value))
                    toppings.id.value to cheese.id.value
                }

            // Capture a line the way createOrder step 4g does: cheese picked from Toppings (+0.50).
            val itemId =
                dbQuery {
                    val item =
                        OrderItem.new {
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("10.0000")
                            this.finalPrice = BigDecimal("10.5000")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = vat
                            this.discount = BigDecimal.ONE
                            this.customPrice = false
                            this.takeAway = false
                            this.transferred = false
                            this.sideNotes = null
                        }
                    ItemExtrasTable.insert {
                        it[orderItemId] = item.id.value
                        it[productExtrasId] = cheeseId
                        it[optionNameSnapshot] = "Cheese"
                        it[priceDeltaSnapshot] = BigDecimal("0.5000")
                        it[groupNameSnapshot] = "Toppings"
                        it[groupId] = toppingsId
                        it[quantity] = 1
                    }
                    item.id.value
                }

            val captured =
                dbQuery {
                    OrderItem
                        .findById(itemId)!!
                        .toOrderItemResponseDto()
                        .extras!!
                        .single()
                }

            assertEquals(toppingsId, captured.groupId, "the group id must survive onto the response")
            assertEquals("0.5000", captured.priceDelta, "the placement's delta, not product_extras.price")

            // The point of carrying it: resolveSelections can find the placement again and re-price
            // this option identically. Without the group id the client had to send 0, which is not an
            // attached group and is rejected outright.
            val reResolved =
                dbQuery {
                    choiceGroupRepo
                        .getOptions(captured.groupId!!)
                        .first { it[ChoiceGroupOptionTable.optionId].value == captured.optionId }[ChoiceGroupOptionTable.priceDelta]
                }
            assertEquals(0, BigDecimal("0.5000").compareTo(reResolved), "re-submit must re-price to the same delta")

            // And the charged per-unit price the client previews for that line: 10.00 + 0.50, matching
            // TableOrderViewModelLineTotalTest's `(10.00 + 2.50)`-shaped assertions on the same formula.
            val finalPrice = (BigDecimal("10.0000") + reResolved.multiply(BigDecimal(1))) * BigDecimal.ONE
            assertEquals(0, BigDecimal("10.5000").compareTo(finalPrice.setScale(4)))
            Unit
        }

    @Test
    fun `missing required group selection is rejected by min-max rule`() =
        runBlocking {
            val category = TestData.createCategory(name = "Mains2")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "Combo",
                    productNumber = "CMB001",
                    price = BigDecimal("10.0000"),
                )

            val groupId =
                dbQuery {
                    val group = choiceGroupRepo.create("Side", null, null, minSelections = 1, maxSelections = 2, isActive = true)
                    val fries = extrasRepo.createExtra("Fries", null, null, BigDecimal("0.0000"), true)
                    choiceGroupRepo.replaceOptions(
                        group.id.value,
                        listOf(ChoiceGroupRepository.OptionRow(fries.id.value, BigDecimal("1.5000"), 0, false)),
                    )
                    productChoiceGroupRepo.replaceForProduct(product.id.value, listOf(group.id.value))
                    group.id.value
                }

            // No selection provided for an attached, active, required group → must be rejected.
            val (chosen, minSel, maxSel) =
                dbQuery {
                    val attached = productChoiceGroupRepo.getGroupIdsForProduct(product.id.value)
                    assertTrue(groupId in attached)
                    val group = ChoiceGroup.findById(groupId)!!
                    Triple(0, group.minSelections, group.maxSelections)
                }
            val violatesRule = chosen < minSel || chosen > maxSel
            assertTrue(violatesRule, "Empty required group must violate the min/max rule")
            Unit
        }
}
