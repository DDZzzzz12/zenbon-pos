package org.example.project.features.tse.verification

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals

/**
 * Route-level tests for POST /api/tse/verify.
 *
 * The Koin-registered [TseVerificationService] resolves file names against [TseExportDirectory]
 * (see ServicesModule.kt) — not a per-test temp dir — so the fixture is written into the real
 * resolved directory directly (unique name per run) and removed afterward, keeping the working
 * tree clean. No DATEV export path is configured by this test's setup data, so the resolver falls
 * back to the relative "exports/tse" directory.
 */
@DisplayName("POST /api/tse/verify")
class TseVerifyRouteTest {
    private lateinit var fixtureFile: File

    @AfterEach
    fun tearDown() {
        if (::fixtureFile.isInitialized) fixtureFile.delete()
    }

    /**
     * A minimal valid USTAR archive with one non-log member (mirrors
     * TseVerificationServiceTest's fixture). It decodes to zero log-message records, so
     * verification returns FAIL with an EMPTY_ARCHIVE finding — an archive proving nothing must
     * never pass (real-TSE QA rt12).
     */
    private fun tarWithInfoCsv(): ByteArray {
        val content = "component:,device\n".toByteArray()
        val header = ByteArray(512)
        "info.csv".toByteArray().copyInto(header, 0)
        "0000644 ".toByteArray().copyInto(header, 100)
        (content.size.toString(8).padStart(11, '0') + " ").toByteArray().copyInto(header, 124)
        "        ".toByteArray().copyInto(header, 148) // checksum field = spaces during compute
        header[156] = '0'.code.toByte() // typeflag: regular file
        "ustar".toByteArray().copyInto(header, 257)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        (checksum.toString(8).padStart(6, '0') + "  ").toByteArray().copyInto(header, 148)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512)) // pad content to a full block
        out.write(ByteArray(1024)) // two zero blocks terminate
        return out.toByteArray()
    }

    @Test
    fun `valid export file returns 200 with a verification report`() {
        val exportsDir = File("exports", "tse").apply { mkdirs() }
        fixtureFile = File(exportsDir, "route-test-${System.nanoTime()}.tar")
        fixtureFile.writeBytes(tarWithInfoCsv())

        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createAdmin()
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/tse/verify") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            TseVerificationDto.VerifyRequestDto.serializer(),
                            TseVerificationDto.VerifyRequestDto(fixtureFile.name),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val report =
                testJson.decodeFromString(
                    TseVerificationDto.ReportDto.serializer(),
                    response.bodyAsText(),
                )
            assertEquals(TseVerificationDto.Verdict.FAIL, report.verdict)
            assertEquals(
                listOf(TseVerificationDto.FindingCode.EMPTY_ARCHIVE),
                report.findings.filter { it.severity == TseVerificationDto.Severity.CRITICAL }.map { it.code },
            )
        }
    }

    @Test
    fun `unauthenticated request is rejected`() =
        testApp { client ->
            // No bearerAuth: rejected by the auth-jwt plugin before the handler (and its
            // call.receive<VerifyRequestDto>()) ever runs, same as the sibling checks in
            // TseAdminRoutesTest (e.g. UpdateConfig) — the body content is irrelevant here.
            val response =
                client.post("/api/tse/verify") {
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
