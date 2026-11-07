package org.example.project.features.product

import kotlinx.coroutines.runBlocking
import org.example.project.features.product.data.repository.ChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductChoiceGroupRepositoryImpl
import org.example.project.features.product.data.repository.ProductExtrasRepositoryImpl
import org.example.project.features.product.domain.repository.ChoiceGroupRepository
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChoiceGroupRepositoryTest {
    private lateinit var repo: ChoiceGroupRepositoryImpl
    private lateinit var extrasRepo: ProductExtrasRepositoryImpl

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repo = ChoiceGroupRepositoryImpl()
        extrasRepo = ProductExtrasRepositoryImpl()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `create group and retrieve by id`() =
        runBlocking {
            val g =
                dbQuery {
                    repo.create("Entrée", "Starter", null, minSelections = 1, maxSelections = 1, isActive = true)
                }
            val found = dbQuery { repo.getById(g.id.value) }
            assertNotNull(found)
            assertEquals("Entrée", found.name)
            assertEquals("Starter", found.enName)
            assertNull(found.chName)
            assertEquals(1, found.minSelections)
            assertEquals(1, found.maxSelections)
            Unit
        }

    @Test
    fun `getAll filters inactive groups by default`() =
        runBlocking {
            dbQuery {
                repo.create("Active Group", null, null, 0, 1, true)
                repo.create("Inactive Group", null, null, 0, 1, false)
            }
            val active = dbQuery { repo.getAll(includeInactive = false) }
            val all = dbQuery { repo.getAll(includeInactive = true) }
            assertEquals(1, active.size)
            assertEquals(2, all.size)
            Unit
        }

    @Test
    fun `update modifies all group fields`() =
        runBlocking {
            val g = dbQuery { repo.create("Old Name", null, null, 0, 1, true) }
            val updated =
                dbQuery {
                    repo.update(g.id.value, "New Name", "EN Name", "中文", 1, 3, false)
                }
            assertNotNull(updated)
            assertEquals("New Name", updated.name)
            assertEquals("EN Name", updated.enName)
            assertEquals("中文", updated.chName)
            assertEquals(1, updated.minSelections)
            assertEquals(3, updated.maxSelections)
            assertEquals(false, updated.isActive)
            Unit
        }

    @Test
    fun `softDelete marks group inactive`() =
        runBlocking {
            val g = dbQuery { repo.create("To Delete", null, null, 0, 1, true) }
            val name = dbQuery { repo.softDelete(g.id.value) }
            assertEquals("To Delete", name)
            val after = dbQuery { repo.getById(g.id.value) }
            assertNotNull(after)
            assertEquals(false, after.isActive)
            Unit
        }

    @Test
    fun `softDelete unbinds the group from every product`() =
        runBlocking {
            val categoryId = TestData.createCategory().id.value
            val first = TestData.createProduct(categoryId, productNumber = "PRD001").id.value
            val second = TestData.createProduct(categoryId, productNumber = "PRD002").id.value
            val deleted = dbQuery { repo.create("Deleted", null, null, 0, 1, true) }
            val kept = dbQuery { repo.create("Kept", null, null, 0, 1, true) }
            val assignments = ProductChoiceGroupRepositoryImpl()
            dbQuery {
                assignments.attachIfNotExists(first, deleted.id.value)
                assignments.attachIfNotExists(first, kept.id.value)
                assignments.attachIfNotExists(second, deleted.id.value)
            }

            dbQuery { repo.softDelete(deleted.id.value) }

            assertEquals(listOf(kept.id.value), dbQuery { assignments.getGroupIdsForProduct(first) })
            assertEquals(emptyList(), dbQuery { assignments.getGroupIdsForProduct(second) })
            Unit
        }

    @Test
    fun `replaceOptions and getOptions round-trip`() =
        runBlocking {
            val g = dbQuery { repo.create("Size", null, null, 1, 1, true) }
            val extra =
                dbQuery {
                    extrasRepo.createExtra("Small", null, null, BigDecimal("0.00"), true)
                }
            dbQuery {
                repo.replaceOptions(
                    g.id.value,
                    listOf(ChoiceGroupRepository.OptionRow(extra.id.value, BigDecimal("1.5000"), 0, true)),
                )
            }
            val options = dbQuery { repo.getOptions(g.id.value) }
            assertEquals(1, options.size)
            Unit
        }

    @Test
    fun `replaceOptions clears previous options`() =
        runBlocking {
            val g = dbQuery { repo.create("Drinks", null, null, 0, 2, true) }
            val e1 = dbQuery { extrasRepo.createExtra("Cola", null, null, BigDecimal("1.00"), true) }
            val e2 = dbQuery { extrasRepo.createExtra("Water", null, null, BigDecimal("0.50"), true) }
            dbQuery {
                repo.replaceOptions(
                    g.id.value,
                    listOf(
                        ChoiceGroupRepository.OptionRow(e1.id.value, BigDecimal.ZERO, 0, false),
                        ChoiceGroupRepository.OptionRow(e2.id.value, BigDecimal.ZERO, 1, false),
                    ),
                )
            }
            // Replace with only e2
            dbQuery {
                repo.replaceOptions(
                    g.id.value,
                    listOf(ChoiceGroupRepository.OptionRow(e2.id.value, BigDecimal("0.2000"), 0, true)),
                )
            }
            val options = dbQuery { repo.getOptions(g.id.value) }
            assertEquals(1, options.size)
            Unit
        }
}
