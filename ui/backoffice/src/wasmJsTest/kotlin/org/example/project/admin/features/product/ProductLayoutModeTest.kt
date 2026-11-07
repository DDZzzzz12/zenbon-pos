package org.example.project.admin.features.product

import androidx.compose.ui.unit.dp
import org.example.project.admin.features.product.presentation.ProductLayoutMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductLayoutModeTest {
    private val tall = 900.dp

    @Test
    fun aPhoneWidthIsCompact() {
        assertEquals(ProductLayoutMode.Compact, ProductLayoutMode.of(390.dp, tall))
        assertEquals(ProductLayoutMode.Compact, ProductLayoutMode.of(599.dp, tall))
    }

    @Test
    fun theBandBetweenPhoneAndTableWithSidebarIsMedium() {
        assertEquals(ProductLayoutMode.Medium, ProductLayoutMode.of(600.dp, tall))
        assertEquals(ProductLayoutMode.Medium, ProductLayoutMode.of(1059.dp, tall))
    }

    @Test
    fun sidebarPlusTableWidthIsLarge() {
        assertEquals(ProductLayoutMode.Large, ProductLayoutMode.of(1060.dp, tall))
        assertEquals(ProductLayoutMode.Large, ProductLayoutMode.of(1440.dp, tall))
    }

    @Test
    fun touchExtraRaisesTheLargeThreshold() {
        assertEquals(ProductLayoutMode.Medium, ProductLayoutMode.of(1070.dp, tall, touchExtra = 24.dp))
        assertEquals(ProductLayoutMode.Large, ProductLayoutMode.of(1084.dp, tall, touchExtra = 24.dp))
    }

    @Test
    fun aShortWindowIsAlwaysCompact() {
        assertEquals(ProductLayoutMode.Compact, ProductLayoutMode.of(1400.dp, 400.dp))
    }
}
