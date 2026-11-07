package org.example.project.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.example.project.core.data.remote.impl.KtorAccountingConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorBackofficeConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorEcrConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorFiscalConfigurationApiClient
import org.example.project.core.data.remote.impl.KtorShopConfigurationApiClient
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.models.setup.VatMappingConfig
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.network.HttpCall
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the HTTP method, URL (query included) and request body of every method of the shop
 * configuration client, in one table, before the client is split by owning area (Stage B2-5, Task C1).
 *
 * The table is the contract: after the split only the lines that construct the clients may change,
 * never an expected entry. The mock answers an empty JSON object, so a response that does not decode
 * into the method's DTO is tolerated — what is under test is the request.
 */
class ConfigurationClientRequestsCharacterisationTest {
    private data class Sent(
        val method: String,
        val url: String,
        val body: String?,
    )

    private class Row(
        val name: String,
        val expected: Sent,
        val call: suspend () -> Unit,
    )

    private val recorded = mutableListOf<Sent>()

    private val shopConfiguration: KtorShopConfigurationApiClient
    private val accounting: KtorAccountingConfigurationApiClient
    private val fiscal: KtorFiscalConfigurationApiClient
    private val ecr: KtorEcrConfigurationApiClient
    private val backoffice: KtorBackofficeConfigurationApiClient

    init {
        val engine =
            MockEngine { request ->
                val outgoing = request.body
                val bodyText =
                    when (outgoing) {
                        is TextContent -> outgoing.text
                        is ByteArrayContent -> outgoing.bytes().decodeToString()
                        else -> null
                    }
                recorded += Sent(request.method.value, request.url.toString(), bodyText)
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val httpCall =
            object : HttpCall {
                override val httpClient: HttpClient = http

                override fun getBaseUrl(): String = "http://localhost/"
            }
        // Client construction: the only lines of this test that change when the client is split.
        shopConfiguration = KtorShopConfigurationApiClient(httpCall)
        accounting = KtorAccountingConfigurationApiClient(httpCall)
        fiscal = KtorFiscalConfigurationApiClient(httpCall)
        ecr = KtorEcrConfigurationApiClient(httpCall)
        backoffice = KtorBackofficeConfigurationApiClient(httpCall)
    }

    private fun get(path: String) = Sent("GET", "http://localhost/$path", null)

    private fun put(
        path: String,
        body: String,
    ) = Sent("PUT", "http://localhost/$path", body)

    private val shop = 7L

    private val rows: List<Row> =
        listOf(
            Row("getVoucherConfiguration", get("api/v1/config/7/voucher-config")) { ecr.getVoucherConfiguration(shop) },
            Row(
                "updateVoucherConfiguration",
                put("api/v1/config/7/voucher-config", """{"voucherConfig":{"enabled":true,"sources":[]}}"""),
            ) {
                ecr.updateVoucherConfiguration(
                    shop,
                    ShopConfigurationDto.UpdateVoucherConfigRequestDto(VoucherConfig(enabled = true, sources = emptyList())),
                )
            },
            Row("getVatMapping", get("api/v1/config/7/vat-mapping")) { ecr.getVatMapping(shop) },
            Row(
                "updateVatMapping",
                put(
                    "api/v1/config/7/vat-mapping",
                    """{"vatMapping":{"FOOD":{"default":"A","toGo":"B","reduced":"B"},"DRINKS":{"default":"A"},""" +
                        """"RETAIL":{"default":"A"},"SERVICE":{"default":"A"},"VOUCHER":{"default":"C"},"OTHERS":{"default":"C"}}}""",
                ),
            ) {
                ecr.updateVatMapping(shop, ShopConfigurationDto.UpdateVatMappingRequestDto(VatMappingConfig.getDefault()))
            },
            Row("getPrintSettings", get("api/v1/config/7/print-settings")) { ecr.getPrintSettings(shop) },
            Row("updatePrintSettings", put("api/v1/config/7/print-settings", """{"enabled":true}""")) {
                ecr.updatePrintSettings(shop, ShopConfigurationDto.UpdatePrintSettingsRequestDto(enabled = true))
            },
            Row("getDocumentStorageConfig", get("api/v1/config/7/document-storage")) { accounting.getDocumentStorageConfig(shop) },
            Row(
                "updateDocumentStorageConfig",
                put("api/v1/config/7/document-storage", """{"path":"/data/docs","maxUploadSizeMb":25}"""),
            ) {
                accounting.updateDocumentStorageConfig(
                    shop,
                    ShopConfigurationDto.UpdateDocumentStorageConfigRequestDto(path = "/data/docs", maxUploadSizeMb = 25),
                )
            },
            Row("getOrderRules", get("api/v1/config/7/order-rules")) { ecr.getOrderRules(shop) },
            Row(
                "updateOrderRules",
                put("api/v1/config/7/order-rules", """{"employeeSelectionOnFreeTable":true}"""),
            ) {
                ecr.updateOrderRules(shop, ShopConfigurationDto.UpdateOrderRulesRequestDto(employeeSelectionOnFreeTable = true))
            },
            Row("getBillingRules", get("api/v1/config/7/billing-rules")) { ecr.getBillingRules(shop) },
            Row(
                "updateBillingRules",
                put("api/v1/config/7/billing-rules", """{"allowCrossEmployeeBilling":true}"""),
            ) {
                ecr.updateBillingRules(shop, ShopConfigurationDto.UpdateBillingRulesRequestDto(allowCrossEmployeeBilling = true))
            },
            Row("getBrowserStations", get("api/v1/config/7/browser-stations")) { shopConfiguration.getBrowserStations(shop) },
            Row("updateBrowserStations", put("api/v1/config/7/browser-stations", """{"roles":["CASHIER"]}""")) {
                shopConfiguration.updateBrowserStations(shop, ShopConfigurationDto.UpdateBrowserStationsRequestDto(roles = listOf("CASHIER")))
            },
            Row("listDirectories(null)", get("api/v1/backup/directories")) { shopConfiguration.listDirectories() },
            Row("listDirectories(path)", get("api/v1/backup/directories?path=%2Fmnt%2Fusb")) { shopConfiguration.listDirectories("/mnt/usb") },
            Row("getDatevXmlConfig", get("api/v1/config/7/datev-xml")) { accounting.getDatevXmlConfig(shop) },
            Row(
                "updateDatevXmlConfig",
                put("api/v1/config/7/datev-xml", """{"kassenName":"Kasse"}"""),
            ) {
                accounting.updateDatevXmlConfig(shop, ShopConfigurationDto.UpdateDatevXmlConfigRequestDto(kassenName = "Kasse"))
            },
            Row("getDatevAccountMapping", get("api/v1/config/7/datev-account-mapping")) { accounting.getDatevAccountMapping(shop) },
            Row(
                "updateDatevAccountMapping",
                put("api/v1/config/7/datev-account-mapping", """{"kontenrahmen":"SKR03","kasseKonto":1000}"""),
            ) {
                accounting.updateDatevAccountMapping(
                    shop,
                    DatevAccountMappingDto.MappingDto(kontenrahmen = Kontenrahmen.SKR03, kasseKonto = 1000),
                )
            },
            Row("getCashBookTransferTarget", get("api/v1/config/7/cash-book-transfer-target")) {
                fiscal.getCashBookTransferTarget(shop)
            },
            Row(
                "updateCashBookTransferTarget",
                put("api/v1/config/7/cash-book-transfer-target", """{"cashBookId":3}"""),
            ) {
                fiscal.updateCashBookTransferTarget(shop, ShopConfigurationDto.UpdateCashBookTransferTargetRequestDto(cashBookId = 3L))
            },
            Row("getKassenbuchBelegAge", get("api/v1/config/7/kassenbuch-beleg-age")) { fiscal.getKassenbuchBelegAge(shop) },
            Row(
                "updateKassenbuchBelegAge",
                put("api/v1/config/7/kassenbuch-beleg-age", """{"maxAgeDays":45}"""),
            ) {
                fiscal.updateKassenbuchBelegAge(shop, ShopConfigurationDto.UpdateKassenbuchBelegAgeRequestDto(maxAgeDays = 45))
            },
            Row("getBreakRulesConfig", get("api/v1/config/7/break-rules")) { backoffice.getBreakRulesConfig(shop) },
            Row(
                "updateBreakRulesConfig",
                put("api/v1/config/7/break-rules", """{"rules":[{"thresholdMinutes":360,"breakMinutes":30}]}"""),
            ) {
                backoffice.updateBreakRulesConfig(
                    shop,
                    ShopConfigurationDto.BreakRulesConfig(listOf(ShopConfigurationDto.BreakRuleEntry(thresholdMinutes = 360, breakMinutes = 30))),
                )
            },
            Row("getKassenMeldungConfig", get("api/v1/config/7/kassen-meldung")) { fiscal.getKassenMeldungConfig(shop) },
            Row("downloadKassenMeldungPdf", get("api/v1/config/7/kassen-meldung/pdf")) { fiscal.downloadKassenMeldungPdf(shop) },
        )

    @Test
    fun everyMethodSendsItsPinnedRequest() =
        runTest {
            val actual = linkedMapOf<String, List<Sent>>()
            for (row in rows) {
                recorded.clear()
                // A response that does not decode into the DTO is tolerated: only the request is pinned.
                runCatching { row.call() }
                actual[row.name] = recorded.toList()
            }
            val expected = rows.associate { it.name to listOf(it.expected) }
            assertEquals(expected, actual)
        }

    @Test
    fun theTableCoversTwentySevenMethods() {
        // listDirectories has two rows (with and without a path), so 28 rows cover 27 methods.
        assertEquals(27, rows.map { it.name.substringBefore('(') }.toSet().size)
    }
}
