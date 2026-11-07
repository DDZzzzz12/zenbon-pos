package org.example.project.routes

import io.ktor.client.request.*
import io.ktor.http.*
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Datev Export Routes")
class DatevExportRoutesTest {
    @Nested
    @Disabled(
        "DISABLED 2026-08-11 (timetracking): datevExportRoutes() is no longer registered in " +
            "Routing.kt — the feature ships disabled for the next release pending its redesign. " +
            "Requests now return 403 (RouteAuthorizationPlugin default-denies unknown /api paths " +
            "before routing), not the 401 these assertions expect. The /api/v1/datev/export/ad-hoc " +
            "cases below are the fiscal export and stay live. Re-enable with the routes.",
    )
    @DisplayName("GET /api/timetracking/datev/preview")
    inner class GetPreview {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/timetracking/datev/preview?year=2026&month=3")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when year missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/preview?month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when month missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/preview?year=2026") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 200 with valid params`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/preview?year=2026&month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
            }
    }

    @Nested
    @Disabled(
        "DISABLED 2026-08-11 (timetracking): datevExportRoutes() is no longer registered in " +
            "Routing.kt — the feature ships disabled for the next release pending its redesign. " +
            "Requests now return 403 (RouteAuthorizationPlugin default-denies unknown /api paths " +
            "before routing), not the 401 these assertions expect. The /api/v1/datev/export/ad-hoc " +
            "cases below are the fiscal export and stay live. Re-enable with the routes.",
    )
    @DisplayName("GET /api/timetracking/datev/export")
    inner class GetExport {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/timetracking/datev/export?year=2026&month=3")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when year missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/export?month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when month missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/export?year=2026") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @Disabled(
        "DISABLED 2026-08-11 (timetracking): datevExportRoutes() is no longer registered in " +
            "Routing.kt — the feature ships disabled for the next release pending its redesign. " +
            "Requests now return 403 (RouteAuthorizationPlugin default-denies unknown /api paths " +
            "before routing), not the 401 these assertions expect. The /api/v1/datev/export/ad-hoc " +
            "cases below are the fiscal export and stay live. Re-enable with the routes.",
    )
    @DisplayName("GET /api/timetracking/datev/pdf")
    inner class GetPdf {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response = client.get("/api/timetracking/datev/pdf?employeeId=emp1&year=2026&month=3")
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }

        @Test
        fun `returns 400 when employeeId missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/pdf?year=2026&month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when year missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/pdf?employeeId=emp1&month=3") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }

        @Test
        fun `returns 400 when month missing`() =
            testApp(
                setupData = { TestData.createAdmin() },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)
                val response = client.get("/api/timetracking/datev/pdf?employeeId=emp1&year=2026") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/datev/export/ad-hoc (AP excludeKassenbuchLinked)")
    inner class AdHocApExclude {
        @Test
        fun `returns 401 without auth even with excludeKassenbuchLinked param`() =
            testApp { client ->
                val response =
                    client.get(
                        "/api/v1/datev/export/ad-hoc?type=AP_LEDGER&startDate=2026-01-01&endDate=2026-12-31&excludeKassenbuchLinked=false",
                    )
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }

    @Nested
    @DisplayName("GET /api/v1/datev/export/ad-hoc (BUCHUNGSSTAPEL_MIT_BELEGEN)")
    inner class AdHocBuchungsstapelBelege {
        @Test
        fun `returns 401 without auth`() =
            testApp { client ->
                val response =
                    client.get(
                        "/api/v1/datev/export/ad-hoc?type=BUCHUNGSSTAPEL_MIT_BELEGEN&startDate=2026-01-01&endDate=2026-12-31",
                    )
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
    }
}
