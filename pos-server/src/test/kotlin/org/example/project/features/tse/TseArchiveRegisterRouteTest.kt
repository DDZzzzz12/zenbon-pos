package org.example.project.features.tse

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.TseTarFixture
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * `POST /api/tse/archives` — "Archiv registrieren" (real-TSE QA rt14). The Koin-wired service resolves
 * names against the real TSE export folder, which with no DATEV path configured is the relative
 * `exports/tse`; the fixture is written there under a unique name and removed afterwards.
 */
@DisplayName("POST /api/tse/archives")
class TseArchiveRegisterRouteTest {
    private lateinit var fixtureFile: File

    @AfterEach
    fun tearDown() {
        if (::fixtureFile.isInitialized) fixtureFile.delete()
    }

    private fun writeArchive(): String {
        val folder = File("exports", "tse").apply { mkdirs() }
        // A unique counter keeps the SHA-256 unique across runs sharing one H2 schema.
        val unique = System.nanoTime() and 0x3FFFFFFF
        fixtureFile = File(folder, "route-archive-$unique.tar")
        fixtureFile.writeBytes(TseTarFixture.tar(listOf(TseTarFixture.logMessage(0xAB, tanr = 7, counter = unique))))
        return fixtureFile.name
    }

    private fun body(fileName: String) =
        testJson.encodeToString(TseDto.RegisterExportArchiveRequestDto.serializer(), TseDto.RegisterExportArchiveRequestDto(fileName))

    @Test
    fun `an administrator registers an archive from the export folder`() {
        val name = writeArchive()
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createAdmin()
        }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)

            val response =
                client.post("/api/tse/archives") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body(name))
                }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val dto = testJson.decodeFromString(TseDto.ExportArchiveDto.serializer(), response.bodyAsText())
            assertEquals(name, dto.fileName)
            assertEquals("AB".repeat(32), dto.tseSerialHex)
            assertEquals(7L, dto.tanrFrom)
            assertEquals(TseExportArchiveOrigin.REGISTERED, dto.origin)
        }
    }

    @Test
    fun `a cashier without the TSE administration capability is refused`() {
        val name = writeArchive()
        testApp(setupData = {
            TestData.createShopInfo()
            TestData.createCashier()
        }) { client ->
            val token = TestAuthHelper.loginAsCashier(client)

            val response =
                client.post("/api/tse/archives") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body(name))
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `an unauthenticated request is rejected`() =
        testApp { client ->
            val response =
                client.post("/api/tse/archives") {
                    contentType(ContentType.Application.Json)
                    setBody(body("x.tar"))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
