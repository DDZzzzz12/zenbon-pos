package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys
import org.example.project.core.utils.SystemRecords

/**
 * The display name of a product category: the localized name for the two system categories, whose
 * stored names (`VOUCHER`, `PFAND`) are identifiers the system resolves them by, and the operator's
 * own name for every other category.
 *
 * Display only: guards and lookups keep comparing the stored name ([SystemRecords]). NEW-13 (adminApp
 * review): Analysen > Katalog showed the raw "VOUCHER".
 */
fun categoryDisplayName(
    categoryName: String,
    language: Language,
): String =
    when (categoryName.trim()) {
        SystemRecords.VOUCHER_CATEGORY_NAME -> getString(EnumKeys.systemCategoryVoucher, language)
        SystemRecords.PFAND_CATEGORY_NAME -> getString(EnumKeys.systemCategoryPfand, language)
        else -> categoryName
    }
