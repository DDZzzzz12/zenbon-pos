package org.example.project.routes

import io.ktor.http.HttpMethod
import org.example.project.infrastructure.security.AccessLevel
import org.example.project.infrastructure.security.ParameterizedRule
import org.example.project.infrastructure.security.RoutePermissions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Route Permission System")
class ParameterizedRuleTest {
    @Nested
    @DisplayName("ParameterizedRule matching")
    inner class Matching {
        @Test
        fun `matches single path parameter`() {
            val rule = ParameterizedRule("/api/employees/{id}", HttpMethod.Get, AccessLevel.Public)
            assertTrue(rule.matches("/api/employees/123", HttpMethod.Get))
            assertTrue(rule.matches("/api/employees/abc-def", HttpMethod.Get))
        }

        @Test
        fun `rejects wrong HTTP method`() {
            val rule = ParameterizedRule("/api/employees/{id}", HttpMethod.Get, AccessLevel.Public)
            assertFalse(rule.matches("/api/employees/123", HttpMethod.Post))
        }

        @Test
        fun `rejects wrong segment count`() {
            val rule = ParameterizedRule("/api/employees/{id}", HttpMethod.Get, AccessLevel.Public)
            assertFalse(rule.matches("/api/employees/123/extra", HttpMethod.Get))
            assertFalse(rule.matches("/api/employees", HttpMethod.Get))
        }

        @Test
        fun `matches path parameter in middle`() {
            val rule = ParameterizedRule("/api/employees/{id}/password", HttpMethod.Patch, AccessLevel.Public)
            assertTrue(rule.matches("/api/employees/123/password", HttpMethod.Patch))
        }

        @Test
        fun `matches multiple path parameters`() {
            val rule = ParameterizedRule("/api/v1/config/{shopId}/vat-mapping", HttpMethod.Put, AccessLevel.Public)
            assertTrue(rule.matches("/api/v1/config/5/vat-mapping", HttpMethod.Put))
        }

        @Test
        fun `rejects mismatched static segments`() {
            val rule = ParameterizedRule("/api/employees/{id}/password", HttpMethod.Patch, AccessLevel.Public)
            assertFalse(rule.matches("/api/employees/123/deactivate", HttpMethod.Patch))
        }
    }

    @Nested
    @DisplayName("RoutePermissions.findRule")
    inner class FindRule {
        @Test
        fun `exact match takes priority over parameterized`() {
            // /api/employees/summary is an exact rule, not matched by /api/employees/{id}.
            // Its access level became Cap in Stage 1; the point of the test is the exact-over-
            // parameterized precedence, which is unchanged.
            val result = RoutePermissions.findRule("/api/employees/summary", HttpMethod.Get)
            assertNotNull(result)
            assertTrue(result is AccessLevel.Cap)
        }

        @Test
        fun `parameterized path matches UUID-like values`() {
            val result = RoutePermissions.findRule("/api/employees/550e8400-e29b-41d4-a716-446655440000", HttpMethod.Get)
            assertNotNull(result)
        }

        @Test
        fun `unregistered route returns null`() {
            val result = RoutePermissions.findRule("/api/nonexistent", HttpMethod.Get)
            assertNull(result)
        }

        @Test
        fun `static routes match exactly`() {
            val result = RoutePermissions.findRule("/api/auth/login", HttpMethod.Post)
            assertNotNull(result)
            assertTrue(result is AccessLevel.Public)
        }

        @Test
        fun `wrong method returns null for static route`() {
            val result = RoutePermissions.findRule("/api/auth/login", HttpMethod.Get)
            assertNull(result)
        }
    }
}
