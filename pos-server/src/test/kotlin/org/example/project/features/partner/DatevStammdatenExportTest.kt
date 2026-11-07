package org.example.project.features.partner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto.StammdatenIssueCode
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto.StammdatenRole
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevstammdaten.DatevStammdatenColumns
import org.example.project.export.datevstammdaten.model.StammdatenAccount
import org.example.project.features.audit.data.repository.TransactionAuditRepositoryImpl
import org.example.project.features.audit.data.service.TransactionAuditServiceImpl
import org.example.project.features.debtor.data.repository.DebtorRepositoryImpl
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.partner.data.repository.BusinessPartnerRepositoryImpl
import org.example.project.features.partner.data.service.BusinessPartnerServiceImpl
import org.example.project.features.partner.data.service.DatevStammdatenExportServiceImpl
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parties → DATEV Personenkonten.
 *
 * The requirement the business-partner model exists for is field 232, `Verknüpftes OPOS-Konto`: a
 * company we both buy from and sell to has two accounts in DATEV's one-row-per-Personenkonto format
 * and cannot be one row, so what stops the advisor reconciling two unrelated-looking rows by hand is
 * each row naming the other. It is asserted here **by value and in both directions** — a link
 * written on only one side would still leave one of the two rows anonymous, which is the failure the
 * whole task is about.
 *
 * Most assertions run against the [StammdatenAccount] list the resolver produces rather than the
 * bytes: that list is where every rule lives, and reading field 232 out of a 254-column CSV proves
 * the same thing more slowly. One test does decode the file, so the two cannot drift apart.
 */
@DisplayName("DATEV Debitoren-/Kreditorenstamm export")
class DatevStammdatenExportTest {
    private val suppliers = SupplierRepositoryImpl()
    private val debtors = DebtorRepositoryImpl()

    private fun service() =
        DatevStammdatenExportServiceImpl(
            shopInfoRepository = ShopInfoRepositoryImpl(),
            configManager = ShopConfigurationManager,
        )

    /**
     * Seeds what the DATEV header needs and the caller may not supply: Berater-/Mandantennummer, and
     * the account mapping the Sachkontenrahmen is read from.
     */
    private fun seedDatevConfig() {
        transaction {
            val shopId =
                ShopInfo
                    .all()
                    .first()
                    .id.value
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_XML_CONFIG.key
                it[ShopConfigurationTable.value] =
                    buildJsonObject {
                        put("enabled", true)
                        put("consultantNumber", "12345")
                        put("clientNumber", "67890")
                    }
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
            val mapping = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, nonCashPaymentTypes = emptyList())
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_ACCOUNT_MAPPING.key
                it[ShopConfigurationTable.value] = Json.encodeToJsonElement(mapping).jsonObject
                it[ShopConfigurationTable.description] = "test"
                it[ShopConfigurationTable.updatedAt] = null
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    /**
     * Every party of the shop with its roles attached — the same shape the service loads, built here
     * so the resolver can be exercised without the file assembly in the way.
     */
    private fun allPartyRoles(): List<DatevStammdatenExportServiceImpl.PartyRoles> =
        BusinessPartner.all().map { party ->
            DatevStammdatenExportServiceImpl.PartyRoles(
                partner = party,
                creditor = Supplier.all().firstOrNull { it.partner.id == party.id },
                debtors = Debtor.all().filter { it.partner.id == party.id },
            )
        }

    private fun resolveAll() = DatevStammdatenExportServiceImpl.resolve(allPartyRoles())

    private fun shop() = ShopInfo.all().first()

    /**
     * Adds a debtor role to an existing party.
     *
     * Deliberately not `DebtorRepositoryImpl.create`: that always creates a **new** party, because a
     * customer typed in by hand is a new company until somebody says otherwise. The both-roles case
     * is reached either by a merge — see the merged-away test — or, as here, by attaching the role
     * directly, which is what the merge itself does.
     */
    private fun addDebtorRole(
        party: BusinessPartner,
        debtorNo: String?,
    ): Debtor =
        Debtor.new {
            this.partner = party
            this.shop = shop()
            this.debtorNo = debtorNo
        }

    @Test
    fun `a party with only a creditor role becomes one row keyed on its creditor number`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    suppliers.create(name = "Metro AG", vatId = "DE123456789").creditorNo = "70001"
                    resolveAll()
                }

            val account = resolution.accounts.single()
            assertEquals("70001", account.konto, "Konto comes from the role, not from the party")
            assertEquals("Metro AG", account.name)
            assertNull(account.verknuepftesOposKonto, "there is no other account to link to")
            assertTrue(resolution.skipped.isEmpty())
        }
    }

    /**
     * The whole point. Two rows, each carrying the *other* account in field 232.
     *
     * Asserted by value in both directions on purpose: a resolver that wrote the link on the Kreditor
     * row only would still pass a test that merely counted rows or checked "a link exists", and the
     * advisor would then find the Debitor row sitting there unattached — exactly the reconciliation
     * by hand this export exists to remove.
     */
    @Test
    fun `a party with both roles produces two rows that name each other in field 232`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    supplier.creditorNo = "70001"
                    addDebtorRole(supplier.partner, debtorNo = "10001")
                    resolveAll()
                }

            assertEquals(1, transaction { BusinessPartner.all().count() }, "both roles are on one party")
            val byKonto = resolution.accounts.associateBy { it.konto }
            assertEquals(setOf("10001", "70001"), byKonto.keys)
            assertEquals("10001", byKonto.getValue("70001").verknuepftesOposKonto, "Kreditor names the Debitor")
            assertEquals("70001", byKonto.getValue("10001").verknuepftesOposKonto, "Debitor names the Kreditor")
        }
    }

    @Test
    fun `a creditor with no creditor number is left out and reported`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    suppliers.create(name = "Unnumbered GmbH", vatId = "DE111111111")
                    resolveAll()
                }

            assertTrue(resolution.accounts.isEmpty(), "no row may be invented for a missing Konto")
            val skipped = resolution.skipped.single()
            assertEquals(StammdatenIssueCode.MISSING_ACCOUNT_NUMBER, skipped.code)
            assertEquals(StammdatenRole.KREDITOR, skipped.role)
            assertEquals("Unnumbered GmbH", skipped.partnerName)
            assertNull(skipped.konto)
        }
    }

    /**
     * Rule 4's other half: an unnumbered counterpart means **no** link on either row. A field 232
     * pointing at a Personenkonto that is not in the file sends the advisor after an account that
     * does not exist — worse than admitting we do not know.
     */
    @Test
    fun `when the debtor role has no number the creditor row is written without a link`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    supplier.creditorNo = "70001"
                    addDebtorRole(supplier.partner, debtorNo = null)
                    resolveAll()
                }

            val account = resolution.accounts.single()
            assertEquals("70001", account.konto)
            assertNull(account.verknuepftesOposKonto, "the counterpart account is not in the file")
            assertEquals(StammdatenRole.DEBITOR, resolution.skipped.single().role)
        }
    }

    @Test
    fun `a postcode carrying a letter is dropped and reported, and the row still goes out`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    val supplier =
                        suppliers.create(name = "Dublin Foods", vatId = "IE1234567X", zip = "D02 X285", city = "Dublin")
                    supplier.creditorNo = "70002"
                    resolveAll()
                }

            val account = resolution.accounts.single()
            assertNull(account.postleitzahl, "DATEV rejects the whole file over one letter here")
            assertEquals("Dublin", account.ort, "the rest of the address still goes out")
            val warning = resolution.warnings.single()
            assertEquals(StammdatenIssueCode.NON_NUMERIC_POSTCODE, warning.code)
            assertEquals("70002", warning.konto, "the warning names the account that lost the field")
            assertTrue(resolution.skipped.isEmpty(), "a dropped optional field is not a skipped row")
        }
    }

    @Test
    fun `a VAT id splits into EU-Land and EU-USt-IdNr`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val resolution =
                transaction {
                    suppliers.create(name = "Metro AG", vatId = "DE123456789").creditorNo = "70001"
                    resolveAll()
                }

            val account = resolution.accounts.single()
            assertEquals("DE", account.euLand)
            assertEquals("123456789", account.euUstId)
        }
    }

    /**
     * A merged-away party is not a company any more — the merge re-pointed its roles at the survivor,
     * so exporting it would either duplicate the survivor's accounts or emit an empty shell.
     */
    @Test
    fun `a party merged into another produces no rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            seedDatevConfig()
            val ids =
                transaction {
                    val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                    supplier.creditorNo = "70001"
                    val debtor =
                        debtors.create(shop = shop(), name = "Metro Deutschland", vatId = "DE999999999", debtorNo = "10001")
                    supplier.partner.id.value to debtor.partner.id.value
                }
            val (survivorId, losingId) = ids
            BusinessPartnerServiceImpl(
                partners = BusinessPartnerRepositoryImpl(),
                auditService = TransactionAuditServiceImpl(TransactionAuditRepositoryImpl(), Json),
            ).merge(survivorId = survivorId, losingId = losingId, actingEmployeeId = "emp-1")

            val result = service().exportTransient()

            assertEquals(2, result.accountCount, "the survivor now carries both roles — two accounts, one company")
            assertEquals(
                2,
                transaction { BusinessPartner.all().count() },
                "the losing row is kept; it just must not be exported",
            )
            val kontos = transaction { resolveAll() }.accounts.map { it.konto }
            assertEquals(listOf("10001", "70001"), kontos.sorted())
        }
    }

    /**
     * The same link, read back out of the bytes DATEV will actually receive, so the resolver
     * assertions above cannot drift away from the file.
     */
    @Test
    fun `the generated CSV carries the link in column 232 of both data rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            seedDatevConfig()
            transaction {
                val supplier = suppliers.create(name = "Metro AG", vatId = "DE123456789")
                supplier.creditorNo = "70001"
                addDebtorRole(supplier.partner, debtorNo = "10001")
            }

            val result = service().exportTransient()

            // Row 1 is the metadata header, row 2 the column names; data rows follow.
            val dataRows =
                String(result.bytes, Charsets.ISO_8859_1)
                    .split("\r\n")
                    .filter { it.isNotEmpty() }
                    .drop(2)
                    .map { it.split(";") }
            assertEquals(2, dataRows.size)
            dataRows.forEach { assertEquals(DatevStammdatenColumns.TOTAL_COLUMNS, it.size) }

            fun field(
                row: List<String>,
                datevPosition: Int,
            ) = row[datevPosition - 1]

            val byKonto = dataRows.associateBy { field(it, DatevStammdatenColumns.KONTO) }
            assertEquals(setOf("10001", "70001"), byKonto.keys)
            assertEquals(
                "10001",
                field(byKonto.getValue("70001"), DatevStammdatenColumns.VERKNUEPFTES_OPOS_KONTO),
            )
            assertEquals(
                "70001",
                field(byKonto.getValue("10001"), DatevStammdatenColumns.VERKNUEPFTES_OPOS_KONTO),
            )
            assertEquals("EXTF_DebitorenKreditoren.csv", result.fileName)
        }
    }
}
