package org.example.project.features.voucher

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.voucher.data.service.VoucherProvisioning
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selling a voucher before the feature is switched on used to answer **500 INTERNAL_ERROR**
 * ("VOUCHER product not found"), because `createVoucher` went straight to the `VOU` product lookup
 * and that product is only seeded when `VOUCHER_CONFIG` is enabled. The operator was shown the
 * symptom instead of the fixable cause, at the wrong severity.
 *
 * Found in a live end-to-end run: a fresh shop, `POST /api/vouchers` -> 500.
 */
@DisplayName("Voucher sale is gated on VOUCHER_CONFIG.enabled")
class VoucherFeatureGateTest {
    private val configRepository = ConfigurationRepository(listOf(VoucherProvisioning()))

    /**
     * A JSON **string**, not a Map: `ConfigurationRepository.valueToJsonElement` only parses JSON out
     * of a String (or passes a JsonElement through) and falls back to `value.toString()` for anything
     * else — a Map would be stored as `{enabled=false, ...}`, which is not JSON and silently reverts
     * the key to its default.
     */
    private fun voucherConfig(enabled: Boolean) =
        """{"enabled":$enabled,"sources":[{"type":"INTERNAL","name":"Internal System Vouchers",""" +
            """"enabled":true,"description":"POS vouchers"}]}"""

    @Test
    fun `selling a voucher while the feature is off is refused with a usable error, not a 500`() {
        testApp(
            setupData = {
                TestData.createAdmin()
                val shop = TestData.createShopInfo()
                shopId = shop.id.value
                TestData.createDefaultVatRates()
                val cash = TestData.createPaymentType("Bar_GATE", isCash = true)
                runBlocking {
                    dbQuery { configRepository.setSetting(shop.id.value, ConfigKey.VOUCHER_CONFIG, voucherConfig(enabled = false)) }
                    // Writing the row is not enough. `ShopConfigurationManager` is a process-wide
                    // singleton with a 30-minute cache that nothing resets between tests, and neither
                    // `module()` nor `initialize()` invalidates it — a previous test's cached settings
                    // would otherwise decide this one. Force the reload here so the gate is exercised
                    // against THIS test's configuration whatever ran before.
                    ShopConfigurationManager.refreshConfiguration()
                }
                cashPaymentTypeId = cash.id.value
            },
        ) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response =
                client.post("/api/vouchers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(
                        testJson.encodeToString(
                            VoucherDto.CreateVoucherRequestDto.serializer(),
                            VoucherDto.CreateVoucherRequestDto(
                                originalValue = "50.00",
                                paymentTypeId = cashPaymentTypeId,
                            ),
                        ),
                    )
                }

            assertEquals(
                HttpStatusCode.Conflict,
                response.status,
                "a disabled feature is a configuration conflict, not a server fault",
            )
            val body = response.bodyAsText()
            assertTrue(
                body.contains("VOUCHER_FEATURE_DISABLED"),
                "the error must name the cause so the operator can fix it; got: $body",
            )
            assertTrue(
                !body.contains("VOUCHER product not found"),
                "the old symptom-level message must not be what the operator sees; got: $body",
            )

            // Put the switch back before leaving. `ShopConfigurationManager` is a process-wide
            // singleton with a 30-minute cache that survives `TestDatabase.teardown()`: the next
            // test gets a fresh H2 database with no shop in it, `loadConfigurationFromDatabase()`
            // then returns null and `initialize()` leaves THIS test's `enabled = false` cached.
            // Restoring it here keeps a disabled feature from leaking into whatever runs next.
            runBlocking {
                dbQuery {
                    configRepository.setSetting(shopId, ConfigKey.VOUCHER_CONFIG, voucherConfig(enabled = true))
                }
                ShopConfigurationManager.refreshConfiguration()
            }
        }
    }

    private var cashPaymentTypeId: Long = 0
    private var shopId: Long = 0
}
