package org.example.project.core.domain.dto.pfand

import kotlinx.serialization.Serializable
import org.example.project.money.Decimal

/** DTOs for the Pfand (deposit) type catalog. */
object PfandTypeDto {
    @Serializable
    data class PfandTypeResponseDto(
        val id: Long,
        val name: String,
        val depositValue: String, // BigDecimal serialized as String
        val vatClass: String,
        val isActive: Boolean,
    )

    @Serializable
    data class CreatePfandTypeRequestDto(
        val name: String,
        val depositValue: String,
        val vatClass: String = "A",
    ) {
        fun validate(): List<String> = validateFields(name, depositValue, vatClass)
    }

    @Serializable
    data class UpdatePfandTypeRequestDto(
        val name: String,
        val depositValue: String,
        val vatClass: String,
        val isActive: Boolean = true,
    ) {
        fun validate(): List<String> = validateFields(name, depositValue, vatClass)
    }

    /**
     * Structural validation only. The VAT-class *existence* as a configured Vat row
     * is enforced by the service (it needs DB access).
     */
    private fun validateFields(
        name: String,
        depositValue: String,
        vatClass: String,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors.add("Name is required")
        // Money is parsed as Decimal, never through a Double (CLAUDE.md "Money").
        if (Decimal.parseOrNull(depositValue)?.let { it > Decimal.ZERO } != true) {
            errors.add("Deposit value must be a positive number")
        }
        if (vatClass !in listOf("A", "B", "C")) errors.add("VAT class must be A, B, or C")
        return errors
    }
}
