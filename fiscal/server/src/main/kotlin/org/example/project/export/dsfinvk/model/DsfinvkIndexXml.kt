/**
 * GDPdU DataSet schema model for the DSFinV-K index.xml master descriptor.
 *
 * index.xml is a GDPdU `DataSet` descriptor: it lists every CSV `Table` in the
 * export together with its ordered `VariableColumn` schema (column name + type +
 * length/accuracy). Auditor software parses each CSV through these ordered column
 * descriptors, so the structure must match the DSFinV-K reference exactly.
 *
 * Emitted by [org.example.project.export.dsfinvk.builder.DsfinvkIndexXmlGenerator].
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.export.dsfinvk.model

/**
 * Container object holding the 20-table GDPdU column schema for the export.
 */
object DsfinvkIndexXml {
    /** Column data type in the GDPdU descriptor. */
    sealed interface ColType

    /** AlphaNumeric column with a maximum length. */
    data class Alpha(
        val maxLength: Int,
    ) : ColType

    /** Numeric column with an optional decimal accuracy (digits after the comma). */
    data class Numeric(
        val accuracy: Int? = null,
    ) : ColType

    /** A single `<VariableColumn>` descriptor. */
    data class Column(
        val name: String,
        val type: ColType,
    )

    /** A single `<Table>` descriptor (one CSV file). */
    data class Table(
        val url: String,
        val name: String,
        val columns: List<Column>,
    )

    /**
     * 20 tables in reference order. Column names copied verbatim from each
     * *CsvExporter HEADER; types/lengths/accuracy transcribed from the reference
     * index.xml (`02_index.xml/index.xml`, DSFinV-K 2.4).
     *
     * [certificateFieldCount] is the number of `TSE_ZERTIFIKAT_*` fields the Stamm_TSE table
     * must declare — it MUST equal what [org.example.project.export.dsfinvk.builder.StammTseCsvExporter]
     * emits into tse.csv, so both sides derive it from the same [StammTseCertificate.fieldCount]
     * over the same rows (audit R2). Defaults to the baseline [StammTseCertificate.MIN_FIELDS]
     * (I and II) for callers that describe the reference schema.
     */
    fun tables(certificateFieldCount: Int = StammTseCertificate.MIN_FIELDS): List<Table> =
        listOf(
            Table(
                "cashpointclosing.csv",
                "Stamm_Abschluss",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("Z_BUCHUNGSTAG", Alpha(25)),
                    Column("TAXONOMIE_VERSION", Alpha(10)),
                    Column("Z_START_ID", Alpha(40)),
                    Column("Z_ENDE_ID", Alpha(40)),
                    Column("NAME", Alpha(60)),
                    Column("STRASSE", Alpha(60)),
                    Column("PLZ", Alpha(10)),
                    Column("ORT", Alpha(62)),
                    Column("LAND", Alpha(3)),
                    Column("STNR", Alpha(20)),
                    Column("USTID", Alpha(15)),
                    Column("Z_SE_ZAHLUNGEN", Numeric(2)),
                    Column("Z_SE_BARZAHLUNGEN", Numeric(2)),
                ),
            ),
            Table(
                "location.csv",
                "Stamm_Orte",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("LOC_NAME", Alpha(60)),
                    Column("LOC_STRASSE", Alpha(60)),
                    Column("LOC_PLZ", Alpha(10)),
                    Column("LOC_ORT", Alpha(62)),
                    Column("LOC_LAND", Alpha(3)),
                    Column("LOC_USTID", Alpha(15)),
                ),
            ),
            Table(
                "cashregister.csv",
                "Stamm_Kassen",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("KASSE_BRAND", Alpha(50)),
                    Column("KASSE_MODELL", Alpha(50)),
                    Column("KASSE_SERIENNR", Alpha(70)),
                    Column("KASSE_SW_BRAND", Alpha(50)),
                    Column("KASSE_SW_VERSION", Alpha(50)),
                    Column("KASSE_BASISWAEH_CODE", Alpha(3)),
                    Column("KEINE_UST_ZUORDNUNG", Alpha(1)),
                ),
            ),
            Table(
                "slaves.csv",
                "Stamm_Terminals",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("TERMINAL_ID", Alpha(50)),
                    Column("TERMINAL_BRAND", Alpha(50)),
                    Column("TERMINAL_MODELL", Alpha(50)),
                    Column("TERMINAL_SERIENNR", Alpha(70)),
                    Column("TERMINAL_SW_BRAND", Alpha(50)),
                    Column("TERMINAL_SW_VERSION", Alpha(50)),
                ),
            ),
            Table(
                "pa.csv",
                "Stamm_Agenturen",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("AGENTUR_ID", Numeric()),
                    Column("AGENTUR_NAME", Alpha(60)),
                    Column("AGENTUR_STRASSE", Alpha(60)),
                    Column("AGENTUR_PLZ", Alpha(10)),
                    Column("AGENTUR_ORT", Alpha(62)),
                    Column("AGENTUR_LAND", Alpha(3)),
                    Column("AGENTUR_STNR", Alpha(20)),
                    Column("AGENTUR_USTID", Alpha(15)),
                ),
            ),
            Table(
                "tse.csv",
                "Stamm_TSE",
                buildList {
                    add(Column("Z_KASSE_ID", Alpha(50)))
                    add(Column("Z_ERSTELLUNG", Alpha(30)))
                    add(Column("Z_NR", Numeric()))
                    add(Column("TSE_ID", Numeric()))
                    add(Column("TSE_SERIAL", Alpha(68)))
                    add(Column("TSE_SIG_ALGO", Alpha(21)))
                    add(Column("TSE_ZEITFORMAT", Alpha(31)))
                    add(Column("TSE_PD_ENCODING", Alpha(5)))
                    add(Column("TSE_PUBLIC_KEY", Alpha(512)))
                    // TSE_ZERTIFIKAT_I..N — one AlphaNumeric/1000 column per 1000-char certificate chunk
                    // (Anhang E p. 78-79). N is shared with tse.csv via StammTseCertificate (audit R2).
                    for (i in 1..certificateFieldCount) {
                        add(Column(StammTseCertificate.fieldName(i), Alpha(StammTseCertificate.CHUNK_SIZE)))
                    }
                },
            ),
            Table(
                "vat.csv",
                "Stamm_USt",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("UST_SCHLUESSEL", Numeric()),
                    Column("UST_SATZ", Numeric(2)),
                    Column("UST_BESCHR", Alpha(55)),
                ),
            ),
            Table(
                "businesscases.csv",
                "Z_GV_Typ",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("GV_TYP", Alpha(30)),
                    Column("GV_NAME", Alpha(40)),
                    Column("AGENTUR_ID", Numeric()),
                    Column("UST_SCHLUESSEL", Numeric()),
                    Column("Z_UMS_BRUTTO", Numeric(5)),
                    Column("Z_UMS_NETTO", Numeric(5)),
                    Column("Z_UST", Numeric(5)),
                ),
            ),
            Table(
                "payment.csv",
                "Z_Zahlart",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("ZAHLART_TYP", Alpha(25)),
                    Column("ZAHLART_NAME", Alpha(60)),
                    Column("Z_ZAHLART_BETRAG", Numeric(2)),
                ),
            ),
            Table(
                "cash_per_currency.csv",
                "Z_Waehrungen",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("ZAHLART_WAEH", Alpha(3)),
                    Column("ZAHLART_BETRAG_WAEH", Numeric(2)),
                ),
            ),
            Table(
                "transactions.csv",
                "Bonkopf",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("BON_NR", Numeric()),
                    Column("BON_TYP", Alpha(30)),
                    Column("BON_NAME", Alpha(60)),
                    Column("TERMINAL_ID", Alpha(50)),
                    Column("BON_STORNO", Alpha(1)),
                    Column("BON_START", Alpha(30)),
                    Column("BON_ENDE", Alpha(30)),
                    Column("BEDIENER_ID", Alpha(50)),
                    Column("BEDIENER_NAME", Alpha(50)),
                    Column("UMS_BRUTTO", Numeric(2)),
                    Column("KUNDE_NAME", Alpha(50)),
                    Column("KUNDE_ID", Alpha(50)),
                    Column("KUNDE_TYP", Alpha(50)),
                    Column("KUNDE_STRASSE", Alpha(60)),
                    Column("KUNDE_PLZ", Alpha(10)),
                    Column("KUNDE_ORT", Alpha(62)),
                    Column("KUNDE_LAND", Alpha(3)),
                    Column("KUNDE_USTID", Alpha(15)),
                    Column("BON_NOTIZ", Alpha(255)),
                ),
            ),
            Table(
                "datapayment.csv",
                "Bonkopf_Zahlarten",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("ZAHLART_TYP", Alpha(25)),
                    Column("ZAHLART_NAME", Alpha(60)),
                    Column("ZAHLWAEH_CODE", Alpha(3)),
                    Column("ZAHLWAEH_BETRAG", Numeric(2)),
                    Column("BASISWAEH_BETRAG", Numeric(2)),
                ),
            ),
            Table(
                "lines.csv",
                "Bonpos",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("POS_ZEILE", Alpha(50)),
                    Column("GUTSCHEIN_NR", Alpha(50)),
                    Column("ARTIKELTEXT", Alpha(255)),
                    Column("POS_TERMINAL_ID", Alpha(50)),
                    Column("GV_TYP", Alpha(30)),
                    Column("GV_NAME", Alpha(40)),
                    Column("INHAUS", Alpha(1)),
                    Column("P_STORNO", Alpha(1)),
                    Column("AGENTUR_ID", Numeric()),
                    Column("ART_NR", Alpha(50)),
                    Column("GTIN", Alpha(50)),
                    Column("WARENGR_ID", Alpha(40)),
                    Column("WARENGR", Alpha(50)),
                    Column("MENGE", Numeric(3)),
                    Column("FAKTOR", Numeric(3)),
                    Column("EINHEIT", Alpha(50)),
                    Column("STK_BR", Numeric(5)),
                ),
            ),
            Table(
                "itemamounts.csv",
                "Bonpos_Preisfindung",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("POS_ZEILE", Alpha(50)),
                    Column("TYP", Alpha(20)),
                    Column("UST_SCHLUESSEL", Numeric()),
                    Column("PF_BRUTTO", Numeric(5)),
                    Column("PF_NETTO", Numeric(5)),
                    Column("PF_UST", Numeric(5)),
                ),
            ),
            Table(
                "subitems.csv",
                "Bonpos_Zusatzinfo",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("POS_ZEILE", Alpha(50)),
                    Column("ZI_ART_NR", Alpha(50)),
                    Column("ZI_GTIN", Alpha(50)),
                    Column("ZI_NAME", Alpha(60)),
                    Column("ZI_WARENGR_ID", Alpha(40)),
                    Column("ZI_WARENGR", Alpha(50)),
                    Column("ZI_MENGE", Numeric(3)),
                    Column("ZI_FAKTOR", Numeric(3)),
                    Column("ZI_EINHEIT", Alpha(50)),
                    Column("ZI_UST_SCHLUESSEL", Numeric()),
                    Column("ZI_BASISPREIS_BRUTTO", Numeric(5)),
                    Column("ZI_BASISPREIS_NETTO", Numeric(5)),
                    Column("ZI_BASISPREIS_UST", Numeric(5)),
                ),
            ),
            Table(
                "transactions_tse.csv",
                "TSE_Transaktionen",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("TSE_ID", Numeric()),
                    Column("TSE_TANR", Numeric()),
                    Column("TSE_TA_START", Alpha(30)),
                    Column("TSE_TA_ENDE", Alpha(30)),
                    Column("TSE_TA_VORGANGSART", Alpha(30)),
                    Column("TSE_TA_SIGZ", Numeric()),
                    Column("TSE_TA_SIG", Alpha(512)),
                    Column("TSE_TA_FEHLER", Alpha(200)),
                    Column("TSE_VORGANGSDATEN", Alpha(1000)),
                ),
            ),
            Table(
                "transactions_vat.csv",
                "Bonkopf_USt",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("UST_SCHLUESSEL", Numeric()),
                    Column("BON_BRUTTO", Numeric(5)),
                    Column("BON_NETTO", Numeric(5)),
                    Column("BON_UST", Numeric(5)),
                ),
            ),
            Table(
                "lines_vat.csv",
                "Bonpos_USt",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("POS_ZEILE", Alpha(50)),
                    Column("UST_SCHLUESSEL", Numeric()),
                    Column("POS_BRUTTO", Numeric(5)),
                    Column("POS_NETTO", Numeric(5)),
                    Column("POS_UST", Numeric(5)),
                ),
            ),
            Table(
                "allocation_groups.csv",
                "Bonkopf_AbrKreis",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("ABRECHNUNGSKREIS", Alpha(50)),
                ),
            ),
            Table(
                "references.csv",
                "Bon_Referenzen",
                listOf(
                    Column("Z_KASSE_ID", Alpha(50)),
                    Column("Z_ERSTELLUNG", Alpha(30)),
                    Column("Z_NR", Numeric()),
                    Column("BON_ID", Alpha(40)),
                    Column("POS_ZEILE", Alpha(50)),
                    Column("REF_TYP", Alpha(20)),
                    Column("REF_NAME", Alpha(40)),
                    Column("REF_DATUM", Alpha(30)),
                    Column("REF_Z_KASSE_ID", Alpha(50)),
                    Column("REF_Z_NR", Numeric()),
                    Column("REF_BON_ID", Alpha(40)),
                ),
            ),
        )
}
