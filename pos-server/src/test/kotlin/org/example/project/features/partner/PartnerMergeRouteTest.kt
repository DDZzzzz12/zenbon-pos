package org.example.project.features.partner

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.partner.BusinessPartnerDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The merge endpoint over HTTP, and the gate in front of it.
 *
 * `PartnerRules` gates `POST /api/partners/merge` on an [org.example.project.infrastructure.security.AccessLevel.AllOf]
 * of `CREDITOR_WRITE` **and** `DEBTOR_WRITE` — one merge re-points a creditor role and a debtor role,
 * so a bookkeeper holding one side must not be able to restructure the other.
 *
 * Until this test, that policy was proven only against `AccessDecision.check` in isolation and
 * against the registry's own consistency checks. Neither shows the arm is *wired*: a declared-but-
 * unenforced arm reads to everyone as protection the server does not actually apply. What is new
 * here is a real request from a real principal holding one half.
 *
 * The mirror case (`DEBTOR_WRITE` only) is deliberately **not** repeated here. `AccessDecisionAllOfTest`
 * already covers both directions of the set arithmetic; what is unproven is the wiring, and one
 * direction demonstrates that the plugin consults the `AllOf` arm at all.
 */
@DisplayName("Partner merge route")
class PartnerMergeRouteTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    /** The two roles are created independently, so they land on two separate parties. */
    private data class TwoParties(
        val creditorPartyId: Long,
        val debtorPartyId: Long,
    )

    private fun seedTwoParties(): TwoParties =
        transaction {
            val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
            val debtor =
                debtors.create(
                    shop = ShopInfo.all().first(),
                    name = "Metro AG",
                    vatId = "DE123456789",
                )
            TwoParties(supplier.partner.id.value, debtor.partner.id.value)
        }

    private fun mergeBody(parties: TwoParties) =
        json.encodeToString(
            BusinessPartnerDto.MergeRequestDto(
                survivorId = parties.creditorPartyId,
                losingId = parties.debtorPartyId,
            ),
        )

    /**
     * A group holding the creditor half and not the debtor half.
     *
     * Built as a row rather than picked from [org.example.project.core.auth.CapabilityBundles],
     * because no seeded bundle splits the two — MANAGER and ACCOUNTANT both carry the pair, which is
     * precisely why the gate would look fine while doing nothing. `SELF_READ` is in the set only so
     * the principal can read its own profile; without it the login flow, not the gate, is what
     * would fail.
     */
    private fun seedCreditorOnlyGroup() {
        transaction {
            val now = currentTimeStamp()
            val groupId =
                PermissionGroupTable.insertAndGetId {
                    it[code] = "CREDITOR_ONLY"
                    it[name] = "Creditor only"
                    it[description] = "holds CREDITOR_WRITE and deliberately not DEBTOR_WRITE"
                    it[isSystem] = false
                    it[isDeletable] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            listOf(
                Capability.SELF_READ,
                Capability.CREDITOR_READ,
                Capability.CREDITOR_WRITE,
            ).forEach { capability ->
                PermissionGroupCapabilityTable.insert {
                    it[group] = groupId
                    it[this.capability] = capability.name
                }
            }
        }
    }

    private fun employeeId(username: String): String =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    private fun baseData() {
        TestData.createShopInfo()
        TestData.createAdmin()
        TestData.createManager()
    }

    @Test
    fun `a caller holding both write capabilities merges the two parties`() =
        testApp(setupData = { baseData() }) { client ->
            val parties = seedTwoParties()
            // MANAGER carries CREDITOR_WRITE and DEBTOR_WRITE both.
            val token = TestAuthHelper.loginAsManager(client)

            val merged =
                client.post("/api/partners/merge") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(mergeBody(parties))
                }
            assertEquals(HttpStatusCode.NoContent, merged.status)

            transaction {
                assertEquals(
                    parties.creditorPartyId,
                    Supplier
                        .all()
                        .single()
                        .partner.id.value,
                )
                assertEquals(
                    parties.creditorPartyId,
                    Debtor
                        .all()
                        .single()
                        .partner.id.value,
                    "the debtor role must have moved onto the survivor",
                )
                assertEquals(
                    parties.creditorPartyId,
                    BusinessPartner
                        .findById(parties.debtorPartyId)
                        ?.mergedInto
                        ?.id
                        ?.value,
                )
            }
        }

    @Test
    fun `a caller holding only CREDITOR_WRITE is refused, and nothing moves`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "CRD001", username = "creditoronly")
                seedCreditorOnlyGroup()
            },
        ) { client ->
            val parties = seedTwoParties()

            // Assign through the API rather than by hand, so the resolver's caches are invalidated
            // exactly as they are in production.
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put("/api/employees/${employeeId("creditoronly")}/permission-group") {
                        bearerAuth(adminToken)
                        setBody(TextContent("""{"groupCode":"CREDITOR_ONLY"}""", ContentType.Application.Json))
                    }.status,
                "precondition: the narrowed group must be assignable",
            )

            val token = TestAuthHelper.loginAs(client, "creditoronly")
            val refused =
                client.post("/api/partners/merge") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(mergeBody(parties))
                }
            assertEquals(
                HttpStatusCode.Forbidden,
                refused.status,
                "holding one half of the pair must not be enough to restructure the other",
            )

            // A status assertion alone would also pass if the gate rejected *after* the write, so
            // the refusal has to be shown to have cost the database nothing.
            transaction {
                assertNull(
                    BusinessPartner.findById(parties.debtorPartyId)?.mergedInto,
                    "a refused merge must not have pointed the losing party anywhere",
                )
                assertEquals(
                    parties.creditorPartyId,
                    Supplier
                        .all()
                        .single()
                        .partner.id.value,
                )
                assertEquals(
                    parties.debtorPartyId,
                    Debtor
                        .all()
                        .single()
                        .partner.id.value,
                    "the debtor role must still sit on its own party",
                )
            }
        }

    @Test
    fun `reading a merged-away id answers with the survivor`() =
        testApp(setupData = { baseData() }) { client ->
            val parties = seedTwoParties()
            val token = TestAuthHelper.loginAsManager(client)

            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .post("/api/partners/merge") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(mergeBody(parties))
                    }.status,
            )

            // The soft merge keeps the losing row so stored references keep resolving; this is the
            // only case that exercises the route's pointer-following read.
            val fetched = client.get("/api/partners/${parties.debtorPartyId}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertEquals(
                parties.creditorPartyId,
                json.decodeFromString<BusinessPartnerDto.ResponseDto>(fetched.bodyAsText()).id,
                "an id that was merged away must answer with the company it became",
            )
        }
}
