package org.example.project.features.setup

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.voucher.data.service.VoucherProvisioning
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins which virtual table [ConfigurationRepository.initializeDefaults] provisions.
 *
 * Until the quick-sale removal (314bf97f9) setup created a QUICK_SALE virtual table, while the
 * VOUCHER table was only created as a side effect of saving voucher settings through `setSetting`
 * — never during `initializeDefaults`, which seeds VOUCHER_CONFIG by inserting straight into
 * ShopConfigurationTable. That left a fresh install with an unusable QUICK_SALE table and no
 * VOUCHER table, so the counter voucher sale
 * (`VoucherServiceImpl.createCounterVoucher`, which opens its session on the VOUCHER table)
 * failed until someone re-saved voucher settings.
 *
 * The QUICK_SALE *location type* is deliberately still seeded, so historical quick-sale bills stay
 * resolvable; what must not come back is a QUICK_SALE *RTable* nothing can open a session on.
 */
@DisplayName("initializeDefaults provisions the VOUCHER virtual table, not QUICK_SALE")
class ConfigurationRepositoryVirtualTableTest {
    private val repository = ConfigurationRepository(listOf(VoucherProvisioning()))

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun tableFor(locationTypeId: Long): RTable? =
        transaction {
            RTable.find { RTableTable.locationTypeId eq locationTypeId }.firstOrNull()
        }

    @Test
    fun `initializeDefaults creates the VOUCHER virtual table the counter sale needs`() {
        val shopId = TestData.createShopInfo().id.value
        assertNull(tableFor(WellKnownLocationTypes.VOUCHER), "precondition: no VOUCHER table yet")

        runBlocking { dbQuery { repository.initializeDefaults(shopId) } }

        val voucherTable =
            assertNotNull(
                tableFor(WellKnownLocationTypes.VOUCHER),
                "initializeDefaults must provision the VOUCHER table",
            )
        assertEquals("VOUCHER", transaction { voucherTable.tableNumber })
    }

    @Test
    fun `initializeDefaults no longer creates a QUICK_SALE table`() {
        val shopId = TestData.createShopInfo().id.value

        runBlocking { dbQuery { repository.initializeDefaults(shopId) } }

        assertNull(
            tableFor(WellKnownLocationTypes.QUICK_SALE),
            "the quick-sale flow was removed; nothing can open a session on such a table",
        )
    }

    @Test
    fun `initializeDefaults is idempotent and does not duplicate the VOUCHER table`() {
        val shopId = TestData.createShopInfo().id.value

        runBlocking {
            dbQuery { repository.initializeDefaults(shopId) }
            dbQuery { repository.initializeDefaults(shopId) }
        }

        val count =
            transaction {
                RTable.find { RTableTable.locationTypeId eq WellKnownLocationTypes.VOUCHER }.count()
            }
        assertEquals(1L, count, "repeated setup must not stack up VOUCHER tables")
    }

    @Test
    fun `test_configuration_repository_without_contributors_has_no_voucher_side_effects`() {
        val shopId = TestData.createShopInfo().id.value

        runBlocking {
            dbQuery {
                ConfigurationRepository().initializeDefaults(shopId)
                ConfigurationRepository().setSetting(shopId, ConfigKey.VOUCHER_CONFIG, VOUCHER_ON)
            }
        }

        assertNull(tableFor(WellKnownLocationTypes.VOUCHER))
        assertNull(transaction { ProductCategory.find { ProductCategoryTable.name eq "VOUCHER" }.firstOrNull() })
        assertNull(transaction { PaymentType.find { PaymentTypeTable.name eq "GUTSCHEIN" }.firstOrNull() })
    }

    @Test
    fun `test_voucher_provisioning_runs_on_setting_write_and_defaults`() {
        val shopId = TestData.createShopInfo().id.value

        runBlocking { dbQuery { repository.initializeDefaults(shopId) } }
        assertNotNull(tableFor(WellKnownLocationTypes.VOUCHER))

        runBlocking { dbQuery { repository.setSetting(shopId, ConfigKey.VOUCHER_CONFIG, VOUCHER_ON) } }

        assertNotNull(transaction { ProductCategory.find { ProductCategoryTable.name eq "VOUCHER" }.firstOrNull() })
        assertNotNull(
            transaction {
                Product.find { (ProductTable.productType eq ProductType.VOUCHER) and (ProductTable.productNumber eq "VOU") }.firstOrNull()
            },
        )
        val payment = transaction { PaymentType.find { PaymentTypeTable.name eq "GUTSCHEIN" }.firstOrNull() }
        assertNotNull(payment)
        assertEquals(true, transaction { payment.isVoucher })
    }

    private companion object {
        const val VOUCHER_ON =
            """{"enabled":true,"sources":[{"type":"INTERNAL","name":"Internal System Vouchers","enabled":true,"description":"POS vouchers"}]}"""
    }
}
