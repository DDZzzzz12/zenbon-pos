/**
 * Utility for reading CSV files from resources.
 *
 * Provides simple CSV parsing functionality for reading data files
 * from the resources directory. Handles CSV format with proper escaping
 * for multi-line descriptions and quoted fields.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.utils.builder

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Simple CSV reader utility for parsing CSV files from resources.
 *
 * Handles basic CSV parsing with support for quoted fields containing
 * commas and newlines. Designed for reading configuration data files.
 */
object CsvReader {
    private val logger = LoggerFactory.getLogger(CsvReader::class.java)

    /**
     * Reads a CSV file from the resources directory and returns parsed data.
     *
     * @param resourcePath Path to CSV file in resources (e.g., "data/accounts.csv")
     * @param delimiter Field delimiter character (default: ';' for semicolon)
     * @param hasHeader Whether the first line contains column headers (default: true)
     * @return List of maps where keys are column headers (or "col0", "col1"... if no header) and values are cell data
     */
    fun readCsvFromResources(
        resourcePath: String,
        delimiter: Char = ';',
        hasHeader: Boolean = true,
    ): List<Map<String, String>> {
        logger.debug("Reading CSV file from resources: $resourcePath")

        return try {
            val inputStream =
                CsvReader::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: throw IllegalArgumentException("CSV file not found: $resourcePath")

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                parseCsvContent(reader.readLines(), resourcePath, delimiter, hasHeader)
            }
        } catch (e: Exception) {
            logger.error("Failed to read CSV from resources: $resourcePath", e)
            emptyList()
        }
    }

    /**
     * Reads a CSV file from a file system path and returns parsed data.
     *
     * @param filePath Absolute or relative file path
     * @param delimiter Field delimiter character (default: ';' for semicolon)
     * @param hasHeader Whether the first line contains column headers (default: true)
     * @return List of maps where keys are column headers (or "col0", "col1"... if no header) and values are cell data
     */
    fun readCsvFromFile(
        filePath: String,
        delimiter: Char = ';',
        hasHeader: Boolean = true,
    ): List<Map<String, String>> {
        logger.debug("Reading CSV file from path: $filePath")

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("CSV file not found: $filePath")
            }

            file.bufferedReader(Charsets.UTF_8).use { reader ->
                parseCsvContent(reader.readLines(), filePath, delimiter, hasHeader)
            }
        } catch (e: Exception) {
            logger.error("Failed to read CSV from file: $filePath", e)
            emptyList()
        }
    }

    /**
     * Parses CSV content from a string (useful for uploaded files via REST API).
     *
     * @param csvContent CSV content as string
     * @param delimiter Field delimiter character (default: ';' for semicolon)
     * @param hasHeader Whether the first line contains column headers (default: true)
     * @return List of maps where keys are column headers (or "col0", "col1"... if no header) and values are cell data
     */
    fun parseCsvString(
        csvContent: String,
        delimiter: Char = ';',
        hasHeader: Boolean = true,
    ): List<Map<String, String>> {
        logger.debug("Parsing CSV content from string")

        return try {
            val lines = csvContent.lines()
            parseCsvContent(lines, "uploaded-content", delimiter, hasHeader)
        } catch (e: Exception) {
            logger.error("Failed to parse CSV string", e)
            emptyList()
        }
    }

    /**
     * Parses CSV content from a byte array (useful for REST API file uploads).
     *
     * @param csvBytes CSV content as byte array
     * @param delimiter Field delimiter character (default: ';' for semicolon)
     * @param hasHeader Whether the first line contains column headers (default: true)
     * @return List of maps where keys are column headers (or "col0", "col1"... if no header) and values are cell data
     */
    fun parseCsvBytes(
        csvBytes: ByteArray,
        delimiter: Char = ';',
        hasHeader: Boolean = true,
    ): List<Map<String, String>> {
        logger.debug("Parsing CSV content from byte array")

        return try {
            val csvContent = csvBytes.toString(Charsets.UTF_8)
            parseCsvString(csvContent, delimiter, hasHeader)
        } catch (e: Exception) {
            logger.error("Failed to parse CSV bytes", e)
            emptyList()
        }
    }

    /**
     * Core CSV parsing logic shared by all read methods.
     *
     * @param lines List of CSV lines
     * @param sourceName Name of the source (for logging)
     * @param delimiter Field delimiter character
     * @param hasHeader Whether the first line contains column headers
     * @return List of maps where keys are column headers and values are cell data
     */
    private fun parseCsvContent(
        lines: List<String>,
        sourceName: String,
        delimiter: Char,
        hasHeader: Boolean,
    ): List<Map<String, String>> {
        if (lines.isEmpty()) {
            logger.warn("CSV content is empty: $sourceName")
            return emptyList()
        }

        return try {
            // Determine headers
            val (headers, dataStartIndex) =
                if (hasHeader) {
                    // First line is header
                    val headerLine = parseCsvLine(lines.first(), delimiter)
                    logger.debug("CSV headers: ${headerLine.joinToString(", ")}")
                    headerLine to 1
                } else {
                    // No header - generate column names (col0, col1, col2, ...)
                    val firstLineFields = parseCsvLine(lines.first(), delimiter)
                    val generatedHeaders = firstLineFields.indices.map { "col$it" }
                    logger.debug("CSV has no header, generated: ${generatedHeaders.joinToString(", ")}")
                    generatedHeaders to 0
                }

            // Parse data rows
            val data =
                lines.drop(dataStartIndex).mapIndexedNotNull { index, line ->
                    try {
                        if (line.trim().isNotEmpty()) {
                            val values = parseCsvLine(line, delimiter)
                            if (values.size == headers.size) {
                                headers.zip(values).toMap()
                            } else {
                                val actualLineNumber = index + dataStartIndex + 1
                                logger.warn("Row $actualLineNumber has ${values.size} values but expected ${headers.size}: $line")
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        val actualLineNumber = index + dataStartIndex + 1
                        logger.error("Error parsing CSV line $actualLineNumber: $line", e)
                        null
                    }
                }

            logger.info("Successfully read ${data.size} records from $sourceName")
            data
        } catch (e: Exception) {
            logger.error("Failed to read CSV file: $sourceName", e)
            emptyList()
        }
    }

    /**
     * Parses a single CSV line handling quoted fields and escaped quotes.
     *
     * @param line CSV line to parse
     * @return List of field values
     */
    private fun parseCsvLine(
        line: String,
        delimiter: Char = ';',
    ): List<String> {
        val result = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            when (val char = line[i]) {
                '"' if !inQuotes -> {
                    // Start of quoted field
                    inQuotes = true
                }
                '"' if inQuotes -> {
                    // Check for escaped quote
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote - add single quote to field
                        currentField.append('"')
                        i++ // Skip next quote
                    } else {
                        // End of quoted field
                        inQuotes = false
                    }
                }
                delimiter if !inQuotes -> {
                    // Field separator - end current field
                    result.add(currentField.toString().trim())
                    currentField.clear()
                }
                else -> {
                    // Regular character - add to current field
                    currentField.append(char)
                }
            }
            i++
        }

        // Add final field
        result.add(currentField.toString().trim())

        return result
    }
}
