package org.example.project.features.monthlyclose

import org.example.project.features.monthlyclose.data.service.computeDeltaPercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonthlyCloseKpiBuilderTest {
    @Test
    fun `positive growth has plus sign`() = assertEquals("+25.00", computeDeltaPercent("125", "100"))

    @Test
    fun `negative growth has minus sign`() = assertEquals("-20.00", computeDeltaPercent("80", "100"))

    @Test
    fun `null previous returns null`() = assertNull(computeDeltaPercent("100", null))

    @Test
    fun `zero previous returns null`() = assertNull(computeDeltaPercent("100", "0"))

    @Test
    fun `unparseable returns null`() = assertNull(computeDeltaPercent("abc", "100"))
}
