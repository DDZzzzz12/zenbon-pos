package org.example.project.admin.features.promotion

import org.example.project.admin.features.promotion.presentation.promotionValidityText
import kotlin.test.Test
import kotlin.test.assertEquals

class PromotionValidityTest {
    @Test
    fun aBoundedPromotionShowsFromAndUntil() {
        assertEquals("01.11.2026 – 30.11.2026", promotionValidityText("01.11.2026", "30.11.2026", "unbefristet"))
    }

    @Test
    fun aMissingEndIsUnlimited() {
        assertEquals("unbefristet", promotionValidityText("01.11.2026", null, "unbefristet"))
        assertEquals("unbefristet", promotionValidityText("01.11.2026", "", "unbefristet"))
    }
}
