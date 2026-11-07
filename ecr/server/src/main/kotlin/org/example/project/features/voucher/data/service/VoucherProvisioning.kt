package org.example.project.features.voucher.data.service

import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.utils.SystemRecords
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.repository.ConfigurationContributor
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.LocationType
import org.example.project.features.table.domain.models.RTable
import org.example.project.utils.config.VoucherConfigUtil
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * Provisions the voucher records (category, product, GUTSCHEIN payment type, VOUCHER virtual table)
 * the platform configuration repository used to create itself. Runs in the caller's transaction.
 */
class VoucherProvisioning : ConfigurationContributor {
    private val logger = LoggerFactory.getLogger(VoucherProvisioning::class.java)

    override fun onSettingWritten(
        key: ConfigKey,
        raw: String,
    ) {
        if (key == ConfigKey.VOUCHER_CONFIG) checkVoucherConfig(raw)
    }

    override fun afterDefaultsInitialized(shopId: Long) {
        // Ensure the VOUCHER virtual table exists — the counter voucher sale opens its session on it
        ensureVoucherTable()
    }

    /**
     * Ensures the VOUCHER virtual table exists.
     *
     * Creates an RTable with LocationType.VOUCHER if one does not already exist.
     * The counter voucher sale (`VoucherServiceImpl.createCounterVoucher`) opens its session
     * on this table, so it has to exist before a voucher can be sold without a table session.
     * Idempotent — safe to call repeatedly.
     *
     * This used to provision a QUICK_SALE table instead. That flow was removed in 314bf97f9 and
     * nothing can open a session on such a table any more, while the VOUCHER table it left behind
     * was only created as a side effect of saving voucher settings through [setSetting] — never
     * during [initializeDefaults], which seeds VOUCHER_CONFIG by inserting straight into
     * ShopConfigurationTable. The QUICK_SALE location type itself is deliberately retained so
     * historical quick-sale bills stay resolvable.
     */
    private fun ensureVoucherTable() {
        val table =
            RTable
                .find {
                    RTableTable.locationTypeId eq WellKnownLocationTypes.VOUCHER
                }.firstOrNull()
        if (table != null) {
            // A table provisioned before V131 on a database the migration did not reach (H2 dev data).
            if (!table.isSystem) table.isSystem = true
        } else {
            val voucherType =
                LocationType.findById(WellKnownLocationTypes.VOUCHER)
                    ?: throw IllegalStateException("LocationType VOUCHER (id=${WellKnownLocationTypes.VOUCHER}) not found in database")
            RTable
                .new {
                    tableNumber = SystemRecords.VOUCHER_TABLE_NUMBER
                    locationTypeRef = voucherType
                    isSystem = true
                    updateAt = currentTimeStamp()
                }.also {
                    logger.info("Created VOUCHER virtual table with ID: ${it.id.value}")
                }
        }
    }

    /**
     * Checks and creates voucher configuration if needed.
     *
     * Ensures that when internal voucher system is enabled, the necessary
     * product category and product entries exist in the database.
     *
     * @param voucherConfig JSON string containing voucher configuration
     */
    private fun checkVoucherConfig(voucherConfig: String) {
        val config = VoucherConfigUtil.parseVoucherConfig(voucherConfig)

        // Only proceed if voucher system is enabled and internal source is active
        if (config.enabled && config.sources.firstOrNull { it.type == VoucherSourceType.INTERNAL }?.enabled == true) {
            // Find or create VOUCHER category (reactivate if it exists but was deactivated)
            val category = ProductCategory.find { ProductCategoryTable.name eq "VOUCHER" }.firstOrNull()

            if (category != null) {
                // Reactivate if it was deactivated
                if (!category.isActive) {
                    category.isActive = true
                    category.updatedAt = currentTimeStamp()
                    logger.info("Reactivated VOUCHER category with ID: ${category.id.value}")
                }
            } else {
                // Create new VOUCHER category
                ProductCategory
                    .new {
                        name = "VOUCHER"
                        enName = "Voucher"
                        chName = "优惠券"
                        description = "Gift vouchers and prepaid certificates"
                        isActive = true
                        sortPosition =
                            BigDecimal.valueOf(999.99) // Place at end of category list (max value for NUMERIC(8,5))
                        updatedAt = currentTimeStamp()
                    }.also {
                        logger.info("Created VOUCHER category with ID: ${it.id.value}")
                    }
            }

            val finalCategory = ProductCategory.find { ProductCategoryTable.name eq "VOUCHER" }.first()

            // Find or create VOUCHER product
            val product =
                Product
                    .find {
                        ProductTable.categoryId eq finalCategory.id and
                            (ProductTable.productType eq ProductType.VOUCHER) and
                            (ProductTable.productNumber eq "VOU")
                    }.firstOrNull()

            if (product != null) {
                if (!product.isActive) {
                    product.isActive = true
                    product.updatedAt = currentTimeStamp()
                    logger.info("Reactivated VOUCHER product with ID: ${product.id.value}")
                }
            } else {
                Product
                    .new {
                        productNumber = "VOU"
                        name = "Voucher"
                        enName = "Voucher"
                        chName = "优惠券"
                        description = "Gift voucher for flexible value redemption"
                        price = BigDecimal.ZERO // Voucher value is dynamic
                        this.category = finalCategory
                        productType = ProductType.VOUCHER
                        code = "" // No barcode for vouchers
                        isActive = true
                        updatedAt = currentTimeStamp()
                    }.also {
                        logger.info("Created VOUCHER product with ID: ${it.id.value}")
                    }
            }

            ensureVoucherTable()

            // Find or create GUTSCHEIN payment type
            val voucherPaymentType = PaymentType.find { PaymentTypeTable.name eq "GUTSCHEIN" }.firstOrNull()
            if (voucherPaymentType != null) {
                if (!voucherPaymentType.isActive || !voucherPaymentType.isVoucher) {
                    voucherPaymentType.isActive = true
                    voucherPaymentType.isVoucher = true
                    voucherPaymentType.updatedAt = currentTimeStamp()
                    logger.info("Reactivated GUTSCHEIN payment type")
                }
            } else {
                PaymentType.new {
                    name = "GUTSCHEIN"
                    enName = "Voucher"
                    chName = "代金券"
                    isCash = false
                    isVoucher = true
                    isActive = true
                    updatedAt = currentTimeStamp()
                }
                logger.info("Created GUTSCHEIN payment type")
            }
        } else {
            // Disable voucher system if internal source is disabled
            // Find existing VOUCHER product and deactivate it
            ProductCategory.find { ProductCategoryTable.name eq "VOUCHER" }.firstOrNull()?.let { category ->
                Product
                    .find {
                        ProductTable.categoryId eq category.id and
                            (ProductTable.productType eq ProductType.VOUCHER) and
                            (ProductTable.productNumber eq "VOU")
                    }.firstOrNull()
                    ?.let { product ->
                        product.isActive = false
                        product.updatedAt = currentTimeStamp()
                        logger.info("Deactivated VOUCHER product (ID: ${product.id.value}) as internal voucher source is disabled")
                    }

                // Optionally deactivate the category as well
                category.isActive = false
                category.updatedAt = currentTimeStamp()
                logger.info("Deactivated VOUCHER category (ID: ${category.id.value}) as internal voucher source is disabled")
            }

            // Deactivate GUTSCHEIN payment type
            PaymentType.find { PaymentTypeTable.name eq "GUTSCHEIN" }.firstOrNull()?.let {
                it.isActive = false
                it.updatedAt = currentTimeStamp()
                logger.info("Deactivated GUTSCHEIN payment type")
            }
        }
    }
}
