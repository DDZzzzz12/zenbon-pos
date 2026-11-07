package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.features.product.database.tables.ChoiceGroupOptionTable
import org.example.project.features.product.database.tables.ChoiceGroupTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An extra a required choice group cannot do without may not stop being offered (QA admin21
 * NOTE-c21): the delete of the ONLY option of an active group with `minSelections = 1` used to be
 * accepted, and the group kept listing the dead option, so every product carrying it became
 * unorderable. The server refuses with 409 `CHOICE_GROUP_OPTION_REQUIRED`; a deleted extra that the
 * group CAN spare leaves the group's option list.
 */
@DisplayName("Extra delete/deactivate vs. required choice groups")
class ExtraRequiredChoiceGroupGuardTest {
    private var groupId = 0
    private var onlyOption = 0
    private var spareOption = 0

    private fun extra(
        name: String,
        active: Boolean = true,
    ): Int =
        ProductExtrasTable
            .insertAndGetId {
                it[ProductExtrasTable.name] = name
                it[price] = BigDecimal("1.5000")
                it[isActive] = active
                it[updatedAt] = currentTimeStamp()
            }.value

    private fun place(optionId: Int) =
        ChoiceGroupOptionTable.insert {
            it[ChoiceGroupOptionTable.groupId] = this@ExtraRequiredChoiceGroupGuardTest.groupId
            it[ChoiceGroupOptionTable.optionId] = optionId
            it[priceDelta] = BigDecimal("1.5000")
        }

    /** A required group (min 1) whose ONLY offered option is [onlyOption]; [withSpare] adds a second one. */
    private fun seed(
        withSpare: Boolean,
        groupActive: Boolean = true,
    ) {
        TestData.createManager()
        transaction {
            groupId =
                ChoiceGroupTable
                    .insertAndGetId {
                        it[name] = "Größe"
                        it[minSelections] = 1
                        it[maxSelections] = 1
                        it[isActive] = groupActive
                        it[updatedAt] = currentTimeStamp()
                    }.value
            onlyOption = extra("Klein")
            place(onlyOption)
            // An inactive extra in the group does not count as an offered option.
            place(extra("Alt", active = false))
            if (withSpare) {
                spareOption = extra("Groß")
                place(spareOption)
            }
        }
    }

    private fun extraActive(id: Int): Boolean =
        transaction { ProductExtrasTable.selectAll().where { ProductExtrasTable.id eq id }.single()[ProductExtrasTable.isActive] }

    @Test
    fun `deleting the only option of a required group answers 409 and keeps the extra`() =
        testApp(setupData = { seed(withSpare = false) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/product-extras/$onlyOption") { bearerAuth(token) }
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, response.status, body)
            assertTrue("CHOICE_GROUP_OPTION_REQUIRED" in body, body)
            assertTrue("Größe" in body, "the refusal names the group: $body")
            assertTrue(extraActive(onlyOption), "the extra must still be offered")
        }

    @Test
    fun `deactivating the only option of a required group answers 409`() =
        testApp(setupData = { seed(withSpare = false) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response =
                client.put("/api/product-extras/$onlyOption") {
                    bearerAuth(token)
                    setBody(TextContent("""{"name":"Klein","price":"1.50","isActive":false}""", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(extraActive(onlyOption))
        }

    @Test
    fun `an extra the group can spare is deleted and leaves the group's option list`() =
        testApp(setupData = { seed(withSpare = true) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/product-extras/$onlyOption") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
            assertFalse(extraActive(onlyOption))

            val group = client.get("/api/choice-groups/$groupId") { bearerAuth(token) }.bodyAsText()
            assertTrue("\"optionId\":$spareOption" in group, group)
            assertFalse("\"optionId\":$onlyOption" in group, "a deleted extra must leave the option list: $group")
            assertFalse("\"name\":\"Alt\"" in group, "an inactive extra is not listed either: $group")
        }

    @Test
    fun `an inactive group does not hold its options hostage`() =
        testApp(setupData = { seed(withSpare = false, groupActive = false) }) { client ->
            val token = TestAuthHelper.loginAsManager(client)
            val response = client.delete("/api/product-extras/$onlyOption") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
            assertFalse(extraActive(onlyOption))
        }
}
