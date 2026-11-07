package org.example.project.features.customexport

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupCapabilityTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The custom export endpoints over HTTP, and the two gates in front of them.
 *
 * Three things are unproven until a real request is made against the running server, and each is
 * invisible to a unit test of the service:
 *
 * - **The catalogue's field filter runs server-side.** `CatalogueCapabilityFilterTest` proves
 *   `ExportCatalogue.visibleTo` filters; it cannot prove the route passes the *caller's* capabilities
 *   into it rather than, say, every capability that exists. A route that resolved the wrong set would
 *   look correct in every unit test and hand a reader the whole catalogue.
 * - **The two tiers are actually distinct.** `CUSTOM_EXPORT_READ` and `CUSTOM_EXPORT_MANAGE` are two
 *   declarations in `CustomExportRules`, and a rule map is easy to write such that both paths resolve
 *   to the same verdict. Only a principal holding exactly one of them shows they do not.
 * - **The rules are wired at all.** Both capabilities gated no route until this feature's routes
 *   existed; an unregistered rule module denies every path in the family with a 403 that reads
 *   exactly like a missing capability.
 *
 * No seeded bundle splits any of these pairs — MANAGER and ACCOUNTANT both carry the export pair
 * *and* `CREDITOR_READ`, which is precisely why a gate could be doing nothing and still look fine. So
 * each case gets a `permission_group` row built for it, assigned through the real API so the
 * resolver's caches are invalidated exactly as they are in production.
 */
@DisplayName("Custom export routes")
class CustomExportRouteTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val suppliers = SupplierRepositoryImpl()

    /**
     * A group holding exactly [capabilities], plus `SELF_READ`.
     *
     * `SELF_READ` is in every set only so the principal can read its own profile; without it the
     * login flow, not the gate under test, is what would fail.
     */
    private fun seedGroup(
        code: String,
        description: String,
        capabilities: List<Capability>,
    ) {
        transaction {
            val now = currentTimeStamp()
            val groupId =
                PermissionGroupTable.insertAndGetId {
                    it[this.code] = code
                    it[name] = code
                    it[this.description] = description
                    it[isSystem] = false
                    it[isDeletable] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            (capabilities + Capability.SELF_READ).distinct().forEach { capability ->
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

    /** Assigns [groupCode] to [username] through the API, asserting the precondition held. */
    private suspend fun assign(
        client: io.ktor.client.HttpClient,
        username: String,
        groupCode: String,
    ): String {
        val adminToken = TestAuthHelper.loginAsAdmin(client)
        assertEquals(
            HttpStatusCode.OK,
            client
                .put("/api/employees/${employeeId(username)}/permission-group") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"groupCode":"$groupCode"}""", ContentType.Application.Json))
                }.status,
            "precondition: the narrowed group must be assignable",
        )
        return TestAuthHelper.loginAs(client, username)
    }

    private fun catalogueOf(body: String): List<CustomExportDto.SubjectDto> = json.decodeFromString(body)

    @Test
    fun `a reader who may see creditors is offered the partner subject`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP001", username = "exportreader")
                seedGroup(
                    code = "EXPORT_AND_CREDITORS",
                    description = "holds CUSTOM_EXPORT_READ and CREDITOR_READ",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val token = assign(client, "exportreader", "EXPORT_AND_CREDITORS")

            val response = client.get("/api/custom-exports/catalogue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val partner =
                catalogueOf(response.bodyAsText()).firstOrNull { it.key == "partner" }
            assertTrue(
                partner != null && partner.fields.isNotEmpty(),
                "a caller holding CREDITOR_READ must be offered the partner subject with its fields",
            )
        }

    /**
     * The case that matters most.
     *
     * The client's filtering is a convenience; this is the control. A caller admitted to the feature
     * but not to creditor data must not be *offered* a creditor column — if the filter lived only in
     * the client, this same request would return every field and the only thing standing between the
     * caller and the data would be a checkbox they can un-hide.
     */
    @Test
    fun `a reader who may not see creditors is offered no partner field`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP002", username = "exportonly")
                seedGroup(
                    code = "EXPORT_NO_CREDITORS",
                    description = "holds CUSTOM_EXPORT_READ and deliberately not CREDITOR_READ",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ),
                )
            },
        ) { client ->
            val token = assign(client, "exportonly", "EXPORT_NO_CREDITORS")

            val response = client.get("/api/custom-exports/catalogue") { bearerAuth(token) }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "admission to the feature is CUSTOM_EXPORT_READ alone; the fields are a separate question",
            )

            val subjects = catalogueOf(response.bodyAsText())
            assertTrue(
                subjects.none { it.key == "partner" },
                "a subject whose every field is hidden must be dropped, not returned empty",
            )
            assertTrue(
                subjects.flatMap { it.fields }.none { it.key.startsWith("partner.") },
                "no partner field may reach a caller without CREDITOR_READ, under any subject",
            )
        }

    /**
     * The picker's section headings are decided server-side, so they have to survive the wire.
     *
     * A unit test of the catalogue proves `ExportField.group` is populated; it cannot prove the DTO
     * mapper copies it. Dropping it there would leave the client with the flat, unlabelled list this
     * slice exists to replace, and every other assertion in this file would still pass.
     */
    @Test
    fun `the catalogue publishes a related field's group and leaves a root field's null`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP005", username = "groupreader")
                seedGroup(
                    code = "EXPORT_AND_CREDITORS_GROUPS",
                    description = "holds CUSTOM_EXPORT_READ and CREDITOR_READ",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val token = assign(client, "groupreader", "EXPORT_AND_CREDITORS_GROUPS")

            val response = client.get("/api/custom-exports/catalogue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val partner =
                catalogueOf(response.bodyAsText()).first { it.key == "partner" }

            val rootField = partner.fields.first { it.key == "partner.name" }
            assertEquals(
                null,
                rootField.group,
                "a column of the subject's own table belongs to no section",
            )

            // The creditor role is reached over the foreign-key graph, so its columns carry the
            // registry's German label for `supplier` as their heading.
            val creditorFields = partner.fields.filter { it.group == "Kreditor-Rolle" }
            assertTrue(
                creditorFields.isNotEmpty(),
                "a caller holding CREDITOR_READ must be offered the creditor role's columns, grouped",
            )
            assertTrue(
                creditorFields.none { it.key.startsWith("partner.") },
                "a grouped field is reached by a relation path, so its key is not the subject's own",
            )
        }

    /**
     * The two facts the picker's aggregate gate is built on, proven over HTTP.
     *
     * Both are additive, defaulted fields, so dropping either from the mapper leaves a catalogue that
     * decodes cleanly and looks entirely correct: `isAggregate` would be false everywhere and the cap
     * would silently be the DTO's default. The picker would then let an operator tick six summary
     * columns and the save would refuse — the very defect the flags exist to prevent.
     */
    @Test
    fun `the catalogue publishes which fields are aggregates and the cap on them`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP006", username = "aggreader")
                seedGroup(
                    code = "EXPORT_AND_CREDITORS_AGG",
                    description = "holds CUSTOM_EXPORT_READ and CREDITOR_READ",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val token = assign(client, "aggreader", "EXPORT_AND_CREDITORS_AGG")

            val response = client.get("/api/custom-exports/catalogue") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)

            val partner = catalogueOf(response.bodyAsText()).first { it.key == "partner" }

            assertEquals(
                CustomExportServiceImpl.DEFAULT_MAX_AGGREGATE_COLUMNS,
                partner.maxAggregateColumns,
                "the cap the picker gates on is the server's own number, not a client constant",
            )
            assertEquals(
                false,
                partner.fields.first { it.key == "partner.name" }.isAggregate,
                "a plain column of the subject's own table is not a summary column",
            )
            val aggregates = partner.fields.filter { it.isAggregate }
            assertTrue(
                aggregates.isNotEmpty(),
                "the partner subject offers summary columns over its invoices, and they must be flagged",
            )
            assertTrue(
                aggregates.all { "~" in it.key },
                "every flagged field is an aggregate by the server's own key encoding — which is " +
                    "exactly why the client is given the flag instead of being told to look for '~'",
            )
        }

    /**
     * The token is shown to work elsewhere first, so the 403 is demonstrably the missing capability
     * and not a broken fixture — an unassigned group or a failed login would 403 identically.
     */
    @Test
    fun `a caller without CUSTOM_EXPORT_READ is refused the catalogue`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP003", username = "creditorsonly")
                seedGroup(
                    code = "CREDITORS_NO_EXPORT",
                    description = "holds CREDITOR_READ and deliberately neither export capability",
                    capabilities = listOf(Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val token = assign(client, "creditorsonly", "CREDITORS_NO_EXPORT")

            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/partners/duplicates") { bearerAuth(token) }.status,
                "precondition: this principal is authenticated and reaches a CREDITOR_READ route",
            )

            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/custom-exports/catalogue") { bearerAuth(token) }.status,
                "the same working token must be refused the catalogue for want of CUSTOM_EXPORT_READ",
            )
        }

    /**
     * The read tier must not carry the manage tier.
     *
     * Reaching the catalogue first is what makes the 403 attributable: the principal is admitted to
     * the feature, so what the POST refuses can only be the second capability.
     */
    @Test
    fun `a reader without CUSTOM_EXPORT_MANAGE may not save a definition`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP004", username = "readnotmanage")
                seedGroup(
                    code = "EXPORT_READ_ONLY",
                    description = "holds CUSTOM_EXPORT_READ and deliberately not CUSTOM_EXPORT_MANAGE",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val token = assign(client, "readnotmanage", "EXPORT_READ_ONLY")

            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/custom-exports/catalogue") { bearerAuth(token) }.status,
                "precondition: the read tier admits this principal to the feature",
            )

            val refused =
                client.post("/api/custom-exports") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(saveBody("Partnerliste"), ContentType.Application.Json))
                }
            assertEquals(
                HttpStatusCode.Forbidden,
                refused.status,
                "defining an export is the manage tier; holding the read tier must not be enough",
            )

            // A status assertion alone would also pass if the gate rejected after the write.
            val manager = TestAuthHelper.loginAsManager(client)
            val listed = client.get("/api/custom-exports") { bearerAuth(manager) }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(
                "[]",
                listed.bodyAsText().trim(),
                "a refused save must not have stored a definition",
            )
        }

    @Test
    fun `running a definition returns the CSV, and it is not a DATEV file`() =
        testApp(setupData = { baseData() }) { client ->
            transaction { suppliers.create(name = "Metro AG", vatId = "DE123456789") }
            // MANAGER carries both export capabilities and CREDITOR_READ.
            val token = TestAuthHelper.loginAsManager(client)

            val saved =
                client.post("/api/custom-exports") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(saveBody("Partnerliste"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val definition = json.decodeFromString<CustomExportDto.DefinitionDto>(saved.bodyAsText())

            val run = client.get("/api/custom-exports/${definition.id}/run") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, run.status)
            val type = run.contentType()
            assertEquals(ContentType("text", "csv"), type?.withoutParameters())
            assertEquals(
                "UTF-8",
                type?.parameter("charset")?.uppercase(),
                "the charset is part of the contract: the writer emits UTF-8 bytes",
            )

            val body = run.bodyAsBytes()
            assertTrue(body.size >= 3, "an export always has at least a heading row")
            assertEquals(
                listOf(0xEF, 0xBB, 0xBF),
                body.take(3).map { it.toInt() and 0xFF },
                "Excel reads a BOM-less UTF-8 file as the system code page and mangles every umlaut",
            )

            val disposition = run.headers[HttpHeaders.ContentDisposition] ?: ""
            assertTrue(disposition.startsWith("attachment"), disposition)
            val fileName = ContentDisposition.parse(disposition).parameter("filename")
            assertEquals("Partnerliste.csv", fileName)
            assertFalse(
                fileName!!.uppercase().startsWith("EXTF"),
                "EXTF is DATEV's prefix; a custom extract must not be mistaken for a DATEV file",
            )
            assertEquals("1", run.headers["X-Export-Row-Count"])
            assertEquals("false", run.headers["X-Export-Truncated"])

            // Cleaned up through the route, which is also the only coverage the DELETE gate gets.
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/custom-exports/${definition.id}") { bearerAuth(token) }.status,
            )
        }

    /**
     * An edit replaces the definition under its own id.
     *
     * Before `PUT /{id}` existed the editor could only POST, so every edit stored a copy. The list
     * afterwards is the proof: exactly one definition, carrying the edited name and columns.
     */
    @Test
    fun `updating a definition replaces it in place`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val definition = saveDefinition(client, token, "Partnerliste")

            val updated =
                client.put("/api/custom-exports/${definition.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(saveBody("Partnerliste neu", withCity = true), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val body = json.decodeFromString<CustomExportDto.DefinitionDto>(updated.bodyAsText())
            assertEquals(definition.id, body.id, "an update keeps the definition's id")

            val listed =
                json.decodeFromString<List<CustomExportDto.DefinitionDto>>(
                    client.get("/api/custom-exports") { bearerAuth(token) }.bodyAsText(),
                )
            assertEquals(1, listed.size, "an edit must not leave a copy beside the original")
            assertEquals("Partnerliste neu", listed.single().name)
            assertEquals(listOf("partner.name", "partner.city"), listed.single().fields.map { it.fieldKey })
        }

    @Test
    fun `updating an unknown definition is 404`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)

            val response =
                client.put("/api/custom-exports/999999") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(saveBody("Partnerliste"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        }

    /** The update runs the same validation as a save, and a refused edit leaves the stored one intact. */
    @Test
    fun `updating with an unknown field is 400 and changes nothing`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val definition = saveDefinition(client, token, "Partnerliste")
            val invalid =
                json.encodeToString(
                    CustomExportDto.SaveDefinitionRequestDto(
                        name = "Kaputt",
                        subjectKey = "partner",
                        fields = listOf(CustomExportDto.DefinitionFieldDto("partner.no_such_column", 0, "X")),
                    ),
                )

            val response =
                client.put("/api/custom-exports/${definition.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(invalid, ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())

            val listed =
                json.decodeFromString<List<CustomExportDto.DefinitionDto>>(
                    client.get("/api/custom-exports") { bearerAuth(token) }.bodyAsText(),
                )
            assertEquals("Partnerliste", listed.single().name, "a refused update must not have written anything")
        }

    /** The server half of the editor's locked Datenbereich picker. */
    @Test
    fun `updating a definition onto another subject is 400`() =
        testApp(setupData = { baseData() }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val definition = saveDefinition(client, token, "Partnerliste")
            val other =
                catalogueOf(client.get("/api/custom-exports/catalogue") { bearerAuth(token) }.bodyAsText())
                    .first { it.key != "partner" }
            val body =
                json.encodeToString(
                    CustomExportDto.SaveDefinitionRequestDto(
                        name = "Umgezogen",
                        subjectKey = other.key,
                        fields = listOf(CustomExportDto.DefinitionFieldDto(other.fields.first { !it.isAggregate }.key, 0, "X")),
                    ),
                )

            val response =
                client.put("/api/custom-exports/${definition.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(body, ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    @Test
    fun `a reader without CUSTOM_EXPORT_MANAGE may not update a definition`() =
        testApp(
            setupData = {
                baseData()
                TestData.createManager(employeeNumber = "EXP005", username = "readnotedit")
                seedGroup(
                    code = "EXPORT_READ_NO_EDIT",
                    description = "holds CUSTOM_EXPORT_READ and deliberately not CUSTOM_EXPORT_MANAGE",
                    capabilities = listOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ),
                )
            },
        ) { client ->
            val manager = TestAuthHelper.loginAsManager(client)
            val definition = saveDefinition(client, manager, "Partnerliste")
            val token = assign(client, "readnotedit", "EXPORT_READ_NO_EDIT")

            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/custom-exports") { bearerAuth(token) }.status,
                "precondition: the read tier admits this principal to the feature",
            )
            val refused =
                client.put("/api/custom-exports/${definition.id}") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(TextContent(saveBody("Übernommen"), ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
        }

    private suspend fun saveDefinition(
        client: io.ktor.client.HttpClient,
        token: String,
        name: String,
    ): CustomExportDto.DefinitionDto {
        val saved =
            client.post("/api/custom-exports") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(TextContent(saveBody(name), ContentType.Application.Json))
            }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        return json.decodeFromString(saved.bodyAsText())
    }

    /** One column of the partner subject — enough to run, small enough to assert about. */
    private fun saveBody(
        name: String,
        withCity: Boolean = false,
    ): String =
        json.encodeToString(
            CustomExportDto.SaveDefinitionRequestDto(
                name = name,
                subjectKey = "partner",
                fields =
                    listOfNotNull(
                        CustomExportDto.DefinitionFieldDto(
                            fieldKey = "partner.name",
                            position = 0,
                            heading = "Name",
                        ),
                        if (withCity) CustomExportDto.DefinitionFieldDto(fieldKey = "partner.city", position = 1, heading = "Ort") else null,
                    ),
            ),
        )
}
