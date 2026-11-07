package org.example.project.features.partner.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto.StammdatenIssueCode
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto.StammdatenIssueDto
import org.example.project.core.domain.dto.partner.DatevStammdatenExportDto.StammdatenRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.VatIdFormat
import org.example.project.export.datevstammdaten.DatevStammdatenExportBuilder
import org.example.project.export.datevstammdaten.model.StammdatenAccount
import org.example.project.export.datevstammdaten.model.StammdatenFileConfig
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.debtor.domain.models.Debtor
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.partner.domain.models.BusinessPartner
import org.example.project.features.partner.domain.service.DatevStammdatenExportService
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.TestOnlyApi
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import java.time.LocalDateTime

/**
 * Turns business partners into DATEV Personenkonten and writes the link between the two accounts of
 * a company that is both.
 *
 * The interesting half is [resolve], which is deliberately free of the database and of the file
 * format: every rule that decides what the advisor sees lives there and can be read end to end.
 * [exportTransient] only loads the rows and hands the result to
 * [DatevStammdatenExportBuilder], which by its own contract judges nothing.
 */
class DatevStammdatenExportServiceImpl(
    private val shopInfoRepository: ShopInfoRepository,
    private val configManager: IShopConfigurationManager,
) : DatevStammdatenExportService {
    /**
     * One company together with the accounting roles it carries — the resolver's whole input.
     *
     * [debtors] is a list because `debtor` has no unique index on `partner_id`, unlike `supplier`.
     * That is not a hypothetical to paper over: every debtor role is a real account and gets its own
     * row. It only changes what can be *linked* — see [resolve].
     */
    @TestOnlyApi
    data class PartyRoles(
        val partner: BusinessPartner,
        val creditor: Supplier?,
        val debtors: List<Debtor>,
    )

    /** What [resolve] produces: the rows, and everything the operator should be told about them. */
    @TestOnlyApi
    data class Resolution(
        val accounts: List<StammdatenAccount>,
        val skipped: List<StammdatenIssueDto>,
        val warnings: List<StammdatenIssueDto>,
    )

    @OptIn(TestOnlyApi::class)
    override suspend fun exportTransient(): DatevStammdatenExportDto.ResultDto {
        val cfg = loadConfig()
        val resolution = dbQuery { resolve(loadParties()) }
        return DatevStammdatenExportDto.ResultDto(
            bytes = DatevStammdatenExportBuilder.build(cfg, resolution.accounts, LocalDateTime.now()),
            fileName = FILE_NAME,
            accountCount = resolution.accounts.size,
            skipped = resolution.skipped,
            warnings = resolution.warnings,
        )
    }

    /**
     * Every party of the shop that still is a company, with its roles attached.
     *
     * Roles are fetched in two queries and grouped in memory rather than per party: a handover is a
     * whole-book read, and the per-party form is a few hundred round trips for no gain.
     *
     * Inactive parties and inactive roles are **included**. A creditor we stopped buying from can
     * still carry open items, and a master-data handover that omits the account those items post to
     * is the failure this export exists to avoid.
     */
    @OptIn(TestOnlyApi::class)
    private fun loadParties(): List<PartyRoles> {
        val shop =
            shopInfoRepository.findById(1L)
                ?: throw PosException(
                    PosErrorCode.RESOURCE_NOT_FOUND,
                    HttpStatusCode.NotFound,
                    "Default shop not found",
                )
        // Rule 5: a merged-away party is not a company any more. Its roles were re-pointed at the
        // survivor by the merge, so exporting it would either duplicate the survivor's accounts or
        // emit an empty shell.
        val parties =
            BusinessPartner
                .find { (BusinessPartnerTable.shopId eq shop.id) and BusinessPartnerTable.mergedIntoPartnerId.isNull() }
                .toList()
        val partyIds = parties.map { it.id }
        val creditorByParty =
            Supplier
                .find { SupplierTable.partnerId inList partyIds }
                .associateBy { it.partner.id.value }
        val debtorsByParty =
            Debtor
                .find { DebtorTable.partnerId inList partyIds }
                .groupBy { it.partner.id.value }
        return parties.map { party ->
            PartyRoles(
                partner = party,
                creditor = creditorByParty[party.id.value],
                debtors = debtorsByParty[party.id.value].orEmpty(),
            )
        }
    }

    /**
     * The DATEV header fields that the data cannot supply.
     *
     * All four come from the shop's own DATEV configuration and **none** from a caller. A
     * Beraternummer that depends on what somebody typed into a request is one that can silently send
     * a shop's whole master data to the wrong advisor.
     *
     * The Sachkontenrahmen is read from the account mapping, its only source. Missing it is refused
     * rather than defaulted: field 27 used to be a hardcoded `"04"` and an SKR03 shop shipped a file
     * declaring SKR04 — a file that imports cleanly against the wrong chart of accounts.
     */
    private suspend fun loadConfig(): StammdatenFileConfig {
        val consultantNumber =
            ShopConfigurationManager.getDatevXmlConsultantNumber()
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "DATEV Beraternummer not configured",
                )
        val clientNumber =
            ShopConfigurationManager.getDatevXmlClientNumber()
                ?: throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    "DATEV Mandantennummer not configured",
                )
        val mapping =
            configManager.getDatevAccountMapping()
                ?: throw PosException(
                    PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED,
                    HttpStatusCode.Conflict,
                    "No DATEV account mapping is configured for this shop, so the Sachkontenrahmen " +
                        "(SKR03 or SKR04) of the master-data file is unknown. Declaring the wrong one " +
                        "makes DATEV read every Personenkonto against the wrong chart of accounts. " +
                        "Configure the mapping under ${DatevBuchungsstapelMappingGate.MAPPING_LOCATION} and export again.",
                )
        return StammdatenFileConfig(
            consultantNumber = consultantNumber,
            clientNumber = clientNumber,
            wirtschaftsjahrBeginn = ShopConfigurationManager.getDatevWirtschaftsjahrBeginn(),
            sachkontenlaenge = ShopConfigurationManager.getDatevSachkontenlaenge(),
            bezeichnung = "Debitoren/Kreditoren",
            skr = mapping.kontenrahmen.datevCode,
        )
    }

    companion object {
        const val FILE_NAME = "EXTF_DebitorenKreditoren.csv"

        /** DATEV's field length for `Name (Adressatentyp Unternehmen)`. */
        private const val MAX_NAME = 50

        /** DATEV's field length for `Kurzbezeichnung`. */
        private const val MAX_KURZBEZEICHNUNG = 15

        /** Shortest VAT id that can be split into a country and a body. */
        private const val MIN_SPLITTABLE_VAT_ID = 3

        /**
         * Parties → Personenkonten. Every rule of the export lives here.
         *
         * 1. One row per **account**, not per party: a company with both roles produces two.
         * 2. `Konto` comes from the role — `supplier.creditorNo`, `debtor.debtorNo`.
         * 3. A role with no account number is skipped and reported. `Konto` is the only truly
         *    mandatory field and there is no honest way to invent one, but one unnumbered creditor
         *    must not deny the advisor the other two hundred, so this reports rather than throws.
         * 4. A party with both roles gets field 232 on both rows, each naming the other — and if
         *    either role has no number, **neither** row gets a link. A link pointing at a row that is
         *    not in the file is worse than no link: the advisor chases an account that does not exist.
         *
         * The rows are ordered by `Konto` so that two exports of unchanged data are byte-identical
         * and a diff between them shows what actually changed.
         */
        @TestOnlyApi
        fun resolve(parties: List<PartyRoles>): Resolution {
            val accounts = mutableListOf<StammdatenAccount>()
            val skipped = mutableListOf<StammdatenIssueDto>()
            val warnings = mutableListOf<StammdatenIssueDto>()

            parties.forEach { party ->
                val creditorKonto = party.creditor?.let { kontoOf(it.creditorNo) }
                val debtorKontos = party.debtors.map { kontoOf(it.debtorNo) }

                // The link needs exactly one counterpart on each side to name. A creditor and *two*
                // numbered debtor roles has no single "the other account", and guessing one would put
                // a wrong Personenkonto in front of the advisor — the one outcome worse than a blank
                // field, which merely says "we do not know".
                val linkable = creditorKonto != null && debtorKontos.size == 1 && debtorKontos.single() != null

                if (party.creditor != null) {
                    if (creditorKonto == null) {
                        skipped += missingNumber(party.partner, StammdatenRole.KREDITOR)
                    } else {
                        accounts +=
                            accountFor(
                                party = party,
                                konto = creditorKonto,
                                role = StammdatenRole.KREDITOR,
                                link = if (linkable) debtorKontos.single() else null,
                                warnings = warnings,
                            )
                    }
                }

                party.debtors.forEachIndexed { index, _ ->
                    val konto = debtorKontos[index]
                    if (konto == null) {
                        skipped += missingNumber(party.partner, StammdatenRole.DEBITOR)
                    } else {
                        accounts +=
                            accountFor(
                                party = party,
                                konto = konto,
                                role = StammdatenRole.DEBITOR,
                                link = if (linkable) creditorKonto else null,
                                warnings = warnings,
                            )
                    }
                }
            }

            return Resolution(
                accounts = accounts.sortedBy { it.konto },
                skipped = skipped.toList(),
                warnings = warnings.toList(),
            )
        }

        /**
         * The company's identity, as one Personenkonto sees it.
         *
         * Bank details ride on the Kreditor row only. The IBANs we hold were learned from supplier
         * invoices — they are where we *pay to*. On a Debitor they would land in the bank block DATEV
         * uses to collect by direct debit, asserting a mandate nobody granted.
         */
        @OptIn(TestOnlyApi::class)
        private fun accountFor(
            party: PartyRoles,
            konto: String,
            role: StammdatenRole,
            link: String?,
            warnings: MutableList<StammdatenIssueDto>,
        ): StammdatenAccount {
            val partner = party.partner
            val vat = splitVatId(partner.vatId)
            val bank = if (role == StammdatenRole.KREDITOR) partner.bankAccounts.firstOrNull() else null
            return StammdatenAccount(
                konto = konto,
                // Truncated here rather than left to DATEV, so what the advisor reads is what we chose.
                name = partner.name.take(MAX_NAME),
                kurzbezeichnung = partner.name.take(MAX_KURZBEZEICHNUNG),
                euLand = vat?.first,
                euUstId = vat?.second,
                steuernummer = partner.taxNumber,
                strasse = partner.street,
                postleitzahl = postcodeOf(partner, konto, role, warnings),
                ort = partner.city,
                land = partner.country,
                iban = bank?.iban,
                bic = bank?.bic,
                bankName = null,
                verknuepftesOposKonto = link,
            )
        }

        /**
         * The postcode, or nothing plus a warning.
         *
         * DATEV's `Postleitzahl` regex rejects a letter and refuses the **whole file** when it finds
         * one — so a single Irish or Dutch postcode would cost the advisor every other row. Dropping
         * the field and saying so keeps the export usable and leaves the fix visible.
         */
        private fun postcodeOf(
            partner: BusinessPartner,
            konto: String,
            role: StammdatenRole,
            warnings: MutableList<StammdatenIssueDto>,
        ): String? {
            val zip = partner.zip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (zip.all { it.isDigit() }) return zip
            warnings +=
                StammdatenIssueDto(
                    code = StammdatenIssueCode.NON_NUMERIC_POSTCODE,
                    role = role,
                    partnerId = partner.id.value,
                    partnerName = partner.name,
                    konto = konto,
                    message =
                        "Postcode \"$zip\" is not digits only; DATEV would reject the whole file, so it " +
                            "was left out of account $konto.",
                )
            return null
        }

        /**
         * `DE123456789` → `("DE", "123456789")`, the split DATEV's fields 9 and 10 want.
         *
         * Positional, and safely so: `business_partner.vat_id` is written through
         * [VatIdFormat.canonical], so it carries no spacing and no lower case. Canonicalized again
         * here anyway — this reads master data that predates that rule.
         *
         * Under three characters there is no body to put in field 10, and a country code alone tells
         * DATEV nothing, so neither field is written.
         */
        private fun splitVatId(raw: String?): Pair<String, String>? {
            val canonical =
                raw?.let { VatIdFormat.canonical(it) }?.takeIf { it.length >= MIN_SPLITTABLE_VAT_ID }
                    ?: return null
            return canonical.take(2) to canonical.drop(2)
        }

        /** An account number is present only when it is non-blank; `" "` is not a Personenkonto. */
        private fun kontoOf(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

        private fun missingNumber(
            partner: BusinessPartner,
            role: StammdatenRole,
        ): StammdatenIssueDto =
            StammdatenIssueDto(
                code = StammdatenIssueCode.MISSING_ACCOUNT_NUMBER,
                role = role,
                partnerId = partner.id.value,
                partnerName = partner.name,
                konto = null,
                message =
                    "\"${partner.name}\" has no ${if (role == StammdatenRole.KREDITOR) "Kreditoren" else "Debitoren"}" +
                        "nummer, so no row was written for it. Assign the number the Steuerberater uses " +
                        "and export again.",
            )
    }
}
