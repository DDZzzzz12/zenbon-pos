package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.PathNormalizer
import org.example.project.infrastructure.security.RoutePermissionsRuleCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PathNormalizerTest {
    private val normalizer = PathNormalizer(setOf("api", "employees", "orders"))

    @Test
    fun `a record id becomes an id placeholder`() {
        assertEquals("/api/employees/{id}", normalizer.normalize("/api/employees/42"))
    }

    @Test
    fun `a code or a name in a path never survives - it is an allowlist, not a number test`() {
        assertEquals("/api/orders/{id}/{id}", normalizer.normalize("/api/orders/SUMMER-SALE/items"))
    }

    @Test
    fun `the root and an empty path are left alone`() {
        assertEquals("/", normalizer.normalize("/"))
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun `the real registry teaches it the literal segments`() {
        val real = PathNormalizer.fromRouteRules(RoutePermissionsRuleCatalog())
        assertEquals("/api/employees/{id}", real.normalize("/api/employees/7"))
        assertEquals("/api/maintenance/logs", real.normalize("/api/maintenance/logs"))
        assertFalse("SUMMER" in real.normalize("/api/vouchers/SUMMER"))
    }
}
