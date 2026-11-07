package org.example.project.core.utils

import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.table.LocationTypeDto

/**
 * The records the system provisions for itself and depends on: they cannot be renamed, deactivated,
 * retyped or deleted by an operator. One source of truth for server (which refuses the write with
 * `SYSTEM_RECORD_PROTECTED`) and clients (which hide the controls).
 *
 * Categories and the voucher article are recognised by name / product type. Tables and location types
 * carry an explicit `isSystem` flag on their DTOs instead, because their names are operator-editable
 * for ordinary rows; [VOUCHER_TABLE_NUMBER] and [LOCATION_TYPE_IDS] only exist so the seed code and
 * the flag migration name the same rows.
 *
 * Provisioning is unaffected: the Pfand type service and the voucher bootstrap create and change these
 * rows through repositories, never through the guarded service entry points.
 */
object SystemRecords {
    const val PFAND_CATEGORY_NAME = "PFAND"
    const val VOUCHER_CATEGORY_NAME = VoucherCatalog.CATEGORY_NAME
    const val VOUCHER_TABLE_NUMBER = "VOUCHER"

    val CATEGORY_NAMES: Set<String> = setOf(PFAND_CATEGORY_NAME, VOUCHER_CATEGORY_NAME)

    /** The virtual location types (Gutschein, Schnellverkauf) the system seeds and resolves by id. */
    val LOCATION_TYPE_IDS: Set<Long> = setOf(WellKnownLocationTypes.VOUCHER, WellKnownLocationTypes.QUICK_SALE)

    /** True for the PFAND and VOUCHER product categories. */
    fun isSystemCategory(categoryName: String): Boolean = categoryName.trim() in CATEGORY_NAMES

    /** True for the voucher article (product type VOUCHER). */
    fun isSystemProduct(productType: String): Boolean = VoucherCatalog.isVoucherProduct(productType)

    /**
     * The location types an operator may pick as an area (floor-plan "Bereich", the table form's
     * location): the virtual Gutschein/Schnellverkauf types are system rows, not places a table or
     * plan can live in.
     */
    fun selectableLocationTypes(types: List<LocationTypeDto.ResponseDto>): List<LocationTypeDto.ResponseDto> = types.filterNot { it.isSystem }

    /** True when an operator may edit or delete the category: the PFAND and VOUCHER ones are system records. */
    fun mayEditCategory(categoryName: String): Boolean = !isSystemCategory(categoryName)

    /** True when an operator may edit or delete the product: the voucher article is a system record. */
    fun mayEditProduct(productType: String): Boolean = !isSystemProduct(productType)

    /** True for a seeded virtual location type id. */
    fun isSystemLocationTypeId(id: Long): Boolean = id in LOCATION_TYPE_IDS
}
