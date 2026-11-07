package org.example.project.infrastructure.security

import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.testutils.SourceRoots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Development mode chooses the principal; it does not skip the check.
 *
 * The distinction is the whole design. A bypass answers a question nobody asks — "does this work
 * for someone holding every capability?" — and makes authorization untestable in the environment
 * where most manual testing happens. Turn a bypass on and an accountant's sidebar shows all 25
 * items, so a working gate and a broken one look identical.
 *
 * Impersonation keeps the gate live: `X-Dev-Act-As: ACCOUNTANT` resolves that group's capabilities
 * and the same check then runs against them.
 */
@DisplayName("Dev-mode impersonation")
class DevImpersonationTest {
    private companion object {
        /** Kotlin files under [SourceRoots.serverMainRoots] after the ecr data move (stage B2-2) added `ecr/data` to the roots: 934 files, floor 930. */
        const val MINIMUM_SCANNED_FILES = 930
    }

    /** `pos-server`'s main sources and, once it exists, their `platform/server` successor. */
    private fun sources(): List<File> =
        SourceRoots.kotlinFiles(SourceRoots.serverMainRoots()).also {
            SourceRoots.requireScanned("DevImpersonationTest", it.size, MINIMUM_SCANNED_FILES)
        }

    /** The one server source at `org/example/project/<relativePath>`, wherever the extraction put it. */
    private fun serverSource(relativePath: String): String =
        SourceRoots.fileUnder(SourceRoots.serverMainRoots(), "org/example/project/$relativePath").readText()

    @Test
    fun `there is exactly one authorization seam for dev mode`() {
        // There used to be two, disagreeing: `CallAuthorization`'s role helpers returned true
        // unconditionally, while the capability gate had no bypass at all. After Stage 1 moved ~181
        // routes onto `Cap`, the surviving bypass only affected handler checks sitting BEHIND a gate
        // that still ran — so it bought nothing and misdescribed what dev mode did.
        val callAuth = serverSource("infrastructure/plugins/CallAuthorization.kt")
        assertFalse(
            callAuth.contains("isDevelopmentMode"),
            "the role helpers must answer honestly in every configuration; dev mode's one seam is " +
                "X-Dev-Act-As impersonation",
        )

        val seams =
            sources()
                .filter { it.readText().contains("isDevelopmentMode") }
                .map { it.name }
                .toSet()

        // JwtConfig declares the flag; Security wires the login bypass and the startup guard;
        // DevImpersonation is the authorization seam. Application only silences the plain-HTTP
        // exposure warning in dev mode and grants nothing. Nothing else may consult it.
        assertEquals(
            setOf("JwtConfig.kt", "Security.kt", "DevImpersonation.kt", "TokenClaimsExtraction.kt", "Application.kt"),
            seams,
            "a new file consults isDevelopmentMode — dev mode grows a second meaning that way",
        )
    }

    @Test
    fun `impersonating a group yields exactly that group's capabilities`() {
        // The property that keeps it useful: what you see is what that group sees, not a superset.
        // A union with the dev user's own ADMIN capabilities would make every group look like ADMIN,
        // which is the bypass by another name.
        val accountant = CapabilityBundles.forGroup("ACCOUNTANT")!!

        assertTrue(Capability.INVOICE_WRITE in accountant)
        assertFalse(Capability.CATALOG_MANAGE in accountant, "an accountant does not manage the catalog")
        assertFalse(Capability.TSE_ADMIN in accountant)
    }

    @Test
    fun `every seeded group is impersonatable by code`() {
        // The header takes a group CODE, so every seeded group must be reachable by its own name —
        // otherwise the feature works for the four role groups and silently not for the ones that
        // most need looking at.
        CapabilityBundles.definitions().forEach { definition ->
            assertEquals(
                definition.capabilities,
                CapabilityBundles.forGroup(definition.code),
                "${definition.code} cannot be impersonated by code",
            )
        }
    }

    @Test
    fun `impersonation replaces rather than widens`() {
        // Asserted on the resolution rule rather than through HTTP: the plugin applies
        // `devImpersonatedCapabilities() ?: <resolved>`, so a named group REPLACES the resolved
        // answer. Stated here because "just union it, it is only dev" is the obvious wrong turn.
        //
        // Matches the SHAPE, not the name of the operand. An earlier version pinned the literal
        // `?: masked` and broke the moment the step-up stage renamed that local to `elevated` — a
        // failure about a rename, dressed up as a failure about impersonation.
        val plugin = serverSource("infrastructure/plugins/RouteAuthorizationPlugin.kt")
        assertTrue(
            Regex("""devImpersonatedCapabilities\(\)\s*\?:\s*\w+""").containsMatchIn(plugin),
            "impersonation must replace the resolved set, not add to it",
        )
        assertFalse(
            Regex("""[+]\s*call\.devImpersonatedCapabilities|devImpersonatedCapabilities\(\)[^\n]*\+""")
                .containsMatchIn(plugin),
            "impersonation must not be unioned into the resolved set",
        )
    }

    @Test
    fun `dev mode refuses to start where a deployment would`() {
        // The pre-existing check ran the other way round — dev mode EXCUSED an insecure JWT secret,
        // so nothing checked dev mode itself. A flag survives a copied .env or an image built from a
        // dev machine, and this one bypasses login entirely.
        val security = serverSource("infrastructure/plugins/Security.kt")
        assertTrue(security.contains("assertDevelopmentModeIsSafeHere"), "the guard is missing")
        listOf("DATABASE_MODE", "KUBERNETES_SERVICE_HOST", "POS_ENVIRONMENT").forEach {
            assertTrue(security.contains(it), "$it is not among the deployment signals")
        }
    }

    @Test
    fun `an unknown group name falls back rather than escalating`() {
        // A typo must send you back to your own capabilities, never to somebody else's — and never
        // to everything. Guarded by returning null on a miss, which the plugin reads as "no
        // impersonation".
        val impersonation = serverSource("infrastructure/plugins/DevImpersonation.kt")
        assertTrue(impersonation.contains("return null"), "an unknown group must fall through to the real answer")
        assertFalse(
            impersonation.contains("Capability.entries.toSet()"),
            "impersonation must never resolve to every capability — that is the bypass this replaced",
        )
    }
}
