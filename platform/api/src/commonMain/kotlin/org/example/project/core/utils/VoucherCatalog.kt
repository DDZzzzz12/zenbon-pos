package org.example.project.core.utils

/**
 * The voucher system's catalogue entries, as the server provisions them when the internal voucher
 * source is switched on: a category named `VOUCHER` holding the article `VOU` of product type
 * `VOUCHER` (`ConfigurationRepository.checkVoucherConfig`).
 *
 * Neither is an orderable menu item. A voucher is sold through the voucher sale (`POST /api/vouchers`,
 * the till's voucher dialog), which creates the voucher record that gives the line its value; ordered
 * from a table's product grid, the article booked a 0,00 sale with no voucher behind it (QA MD-10).
 * Clients therefore keep both out of their order grids, and the server refuses the article on
 * `POST /api/orders` with `VOUCHER_PRODUCT_NOT_ORDERABLE`.
 */
object VoucherCatalog {
    const val CATEGORY_NAME = "VOUCHER"
    const val PRODUCT_TYPE = "VOUCHER"

    /** True for the voucher article, which must never appear in an order grid. */
    fun isVoucherProduct(productType: String): Boolean = productType == PRODUCT_TYPE

    /** True for the voucher system's own category, which holds nothing orderable. */
    fun isVoucherCategory(categoryName: String): Boolean = categoryName == CATEGORY_NAME
}
