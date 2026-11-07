package org.example.project.features.product.domain.repository

import org.example.project.features.product.domain.models.ChoiceGroup
import org.jetbrains.exposed.v1.core.ResultRow

interface ChoiceGroupRepository {
    fun getAll(includeInactive: Boolean = false): List<ChoiceGroup>

    fun getById(id: Int): ChoiceGroup?

    fun create(
        name: String,
        enName: String?,
        chName: String?,
        minSelections: Int,
        maxSelections: Int,
        isActive: Boolean,
    ): ChoiceGroup

    fun update(
        id: Int,
        name: String,
        enName: String?,
        chName: String?,
        minSelections: Int,
        maxSelections: Int,
        isActive: Boolean,
    ): ChoiceGroup?

    fun softDelete(id: Int): String // returns name

    fun getUpdatedAfter(lastSyncTime: kotlinx.datetime.LocalDateTime): List<ChoiceGroup>

    // Options (choice_group_option rows) returned as raw ResultRow for mapping in the service layer.
    fun getOptions(groupId: Int): List<ResultRow>

    fun replaceOptions(
        groupId: Int,
        options: List<OptionRow>,
    )

    /**
     * Active choice groups that would be left with fewer offered options than their
     * `minSelections` if the extra [optionId] stopped being offered (deleted or deactivated).
     *
     * An option counts as offered while its extra is active; the configurator picks distinct
     * options, so a required group with fewer offered options than its minimum can never be
     * satisfied and makes every product carrying it unorderable (admin21 NOTE-c21).
     */
    fun groupsBrokenByWithdrawingOption(optionId: Int): List<ChoiceGroup>

    /**
     * Bumps `updated_at` of every group placing [optionId], so a delta sync re-delivers those
     * groups after the option's extra changed state.
     */
    fun touchGroupsContainingOption(optionId: Int): List<Int>

    data class OptionRow(
        val optionId: Int,
        val priceDelta: java.math.BigDecimal,
        val sortPosition: Int,
        val isDefault: Boolean,
    )
}
