package org.example.project.ui.components.employee

import androidx.compose.ui.graphics.Color

data class EmployeeColorPair(
    val border: Color,
    val background: Color,
)

object EmployeeColors {
    private val lightPalette =
        listOf(
            Color(0xFF2563EB), // Blue
            Color(0xFF0D9488), // Teal
            Color(0xFF16A34A), // Green
            Color(0xFFEA580C), // Orange
            Color(0xFFDC2626), // Red
            Color(0xFF9333EA), // Purple
            Color(0xFFEC4899), // Pink
            Color(0xFF4F46E5), // Indigo
            Color(0xFF0891B2), // Cyan
            Color(0xFFF59E0B), // Amber
            Color(0xFF65A30D), // Lime
            Color(0xFF78350F), // Brown
        )

    private val darkPalette =
        listOf(
            Color(0xFF60A5FA), // Blue
            Color(0xFF2DD4BF), // Teal
            Color(0xFF4ADE80), // Green
            Color(0xFFFB923C), // Orange
            Color(0xFFF87171), // Red
            Color(0xFFC084FC), // Purple
            Color(0xFFF472B6), // Pink
            Color(0xFF818CF8), // Indigo
            Color(0xFF22D3EE), // Cyan
            Color(0xFFFBBF24), // Amber
            Color(0xFFA3E635), // Lime
            Color(0xFFA16207), // Brown
        )

    fun getColor(
        employeeNumber: String?,
        isDark: Boolean,
        fallback: Color,
    ): Color {
        if (employeeNumber == null) return fallback
        val index = (employeeNumber.hashCode() and Int.MAX_VALUE) % 12
        return if (isDark) darkPalette[index] else lightPalette[index]
    }

    fun getColorPair(
        employeeNumber: String?,
        isDark: Boolean,
        fallback: Color,
    ): EmployeeColorPair {
        val border = getColor(employeeNumber, isDark, fallback)
        return EmployeeColorPair(
            border = border,
            background = border.copy(alpha = 0.15f),
        )
    }
}
