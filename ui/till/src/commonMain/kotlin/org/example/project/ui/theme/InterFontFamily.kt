package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import pos.composeapp.generated.resources.Res
import pos.composeapp.generated.resources.inter_bold
import pos.composeapp.generated.resources.inter_medium
import pos.composeapp.generated.resources.inter_regular
import pos.composeapp.generated.resources.inter_semibold

/** Inter from composeApp's resources; the type scale itself lives in shared-ui ([posTypography]). */
@Composable
fun interFontFamily(): FontFamily =
    FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
