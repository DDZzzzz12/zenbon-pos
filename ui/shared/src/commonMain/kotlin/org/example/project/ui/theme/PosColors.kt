package org.example.project.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PosColors(
    val posAvailable: Color,
    val posAvailableBg: Color,
    val posOccupied: Color,
    /** Text tone for the amber status: [posOccupied] is a dot/fill colour and too light for body text on a light surface. */
    val posOccupiedInk: Color,
    val posReserved: Color,
    val posBilling: Color,
    val posError: Color,
)

val PosLightSemanticColors =
    PosColors(
        posAvailable = Color(0xFF15803D),
        posAvailableBg = Color(0xFFDCFCE7),
        posOccupied = Color(0xFFD97706),
        posOccupiedInk = Color(0xFFB45309),
        posReserved = Color(0xFF2563EB),
        posBilling = Color(0xFF7C3AED),
        posError = Color(0xFFB91C1C),
    )

val PosDarkSemanticColors =
    PosColors(
        posAvailable = Color(0xFF86EFAC),
        posAvailableBg = Color(0xFF14532D),
        posOccupied = Color(0xFFFCD34D),
        posOccupiedInk = Color(0xFFFCD34D),
        posReserved = Color(0xFF93C5FD),
        posBilling = Color(0xFFC4B5FD),
        posError = Color(0xFFFCA5A5),
    )

val LocalPosColors = staticCompositionLocalOf { PosLightSemanticColors }
