package org.example.project.core.data.models

import org.example.project.money.Decimal

data class SelectedOption(
    val optionId: Int,
    val groupId: Int,
    val name: String,
    val priceDelta: Decimal,
    val groupName: String,
    val quantity: Int = 1,
)
