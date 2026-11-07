package org.example.project.export.dsfinvk.builder

import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto

/**
 * Exporter for pa.csv (Stamm_Agenturen / agency master data).
 *
 * Per DSFinV-K spec 3.2.5. Generates header-only CSV when no agencies exist.
 */
object StammAgenturenCsvExporter {
    private val HEADER =
        DsfinvkCsvUtils.joinRow(
            "Z_KASSE_ID",
            "Z_ERSTELLUNG",
            "Z_NR",
            "AGENTUR_ID",
            "AGENTUR_NAME",
            "AGENTUR_STRASSE",
            "AGENTUR_PLZ",
            "AGENTUR_ORT",
            "AGENTUR_LAND",
            "AGENTUR_STNR",
            "AGENTUR_USTID",
        )

    /**
     * Generates pa.csv content. Returns header-only CSV when rows is empty.
     */
    fun generateCsv(rows: List<DsfinvkExportDto.StammAgenturenRowDto> = emptyList()): ByteArray {
        val dataRows =
            rows.map { row ->
                DsfinvkCsvUtils.joinRow(
                    DsfinvkCsvUtils.escapeString(row.zKasseId),
                    DsfinvkCsvUtils.escapeString(row.zErstellung),
                    row.zNr.toString(),
                    row.agenturId.toString(),
                    DsfinvkCsvUtils.escapeString(row.agenturName),
                    DsfinvkCsvUtils.escapeString(row.agenturStrasse),
                    DsfinvkCsvUtils.escapeString(row.agenturPlz),
                    DsfinvkCsvUtils.escapeString(row.agenturOrt),
                    DsfinvkCsvUtils.escapeString(row.agenturLand),
                    DsfinvkCsvUtils.escapeString(row.agenturStNr),
                    DsfinvkCsvUtils.escapeString(row.agenturUstId),
                )
            }
        return DsfinvkCsvUtils.buildCsv(HEADER, dataRows)
    }
}
