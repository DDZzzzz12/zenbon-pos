package org.example.project.features.document

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `DocumentRepositoryImpl.buildSearchQuery`'s `needsBankStatementJoin` originally covered
 * `bankName`/`statementNumber`/`searchTerm` but not `minAmount`/`maxAmount`, while the amount
 * predicates always reference `DocumentBankStatementMetadataTable.closingBalance` regardless of
 * which fields were set (`needsInvoiceJoin` did include them). Filtering by amount alone therefore
 * built a WHERE clause against a table absent from the FROM clause, surfacing to the client as a
 * 500. This pins the fix: `needsBankStatementJoin` must also fire for `minAmount`/`maxAmount`.
 */
@DisplayName("Document search — amount-only filters must not omit the bank statement join")
class DocumentSearchAmountFilterTest {
    @Test
    fun `searching by minAmount alone succeeds`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/documents?minAmount=10.00") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `searching by maxAmount alone succeeds`() =
        testApp(
            setupData = { TestData.createAdmin() },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.get("/api/documents?maxAmount=100.00") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
