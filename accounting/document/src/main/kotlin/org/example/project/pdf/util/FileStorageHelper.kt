package org.example.project.pdf.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import org.example.project.core.domain.enums.document.DocumentType
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * File Storage Helper Utility
 *
 * Provides secure file storage operations for PDF management including:
 * - Path generation and validation
 * - Filename sanitization
 * - Directory creation
 * - Security checks (path traversal prevention)
 * - File size validation
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Helper object for secure PDF file storage operations.
 *
 * Handles path generation, filename sanitization, and security validations
 * to prevent path traversal attacks and ensure organized file storage.
 */
object FileStorageHelper {
    private val logger = LoggerFactory.getLogger(FileStorageHelper::class.java)

    /** No registered IANA type exists for MT940; this is the de-facto name used by banking tools. */
    const val MEDIA_TYPE_MT940 = "application/x-swift-mt940"

    /**
     * Sanitizes a filename to prevent security issues.
     *
     * Removes or replaces dangerous characters that could be used for
     * path traversal attacks or cause file system issues.
     *
     * @param filename Original filename to sanitize
     * @return Sanitized filename safe for file system use
     *
     * Security measures:
     * - Removes ".." sequences (path traversal)
     * - Removes "/" and "\" (directory separators)
     * - Removes null bytes
     * - Limits length to 255 characters
     * - Preserves file extension
     */
    fun sanitizeFilename(filename: String): String {
        var sanitized =
            filename
                .replace("..", "")
                .replace("/", "")
                .replace("\\", "")
                .replace("\u0000", "") // Remove null bytes
                .trim()

        // Limit filename length while preserving extension
        if (sanitized.length > 255) {
            val extension = sanitized.substringAfterLast('.', "")
            val nameWithoutExt = sanitized.substringBeforeLast('.')
            val maxNameLength = 255 - extension.length - 1 // -1 for the dot
            sanitized = nameWithoutExt.take(maxNameLength) + ".$extension"
        }

        return sanitized
    }

    /**
     * Generates a standardized filename for a PDF document.
     *
     * Uses OCR-extracted metadata when available for meaningful filenames.
     * Falls back to date + content hash suffix for uniqueness.
     *
     * Filename patterns:
     * - INVOICE: Rechnung_{vendor}_{invoiceNr}_{date}_{hash4}.pdf
     * - BANK_STATEMENT: Kontoauszug_{bank}_{stmtNr}_{date}_{hash4}.pdf
     * - CONTRACTS: Vertrag_{description}_{date}_{hash4}.pdf
     * - CUSTOM: Sonstige_{description}_{date}_{hash4}.pdf
     *
     * @param type PDF document type
     * @param timestamp Upload/generation timestamp
     * @param contentHash SHA-256 content hash (first 4 chars used for uniqueness)
     * @param vendorName Vendor name from OCR (for INVOICE)
     * @param invoiceNumber Invoice number from OCR (for INVOICE)
     * @param bankName Bank name from OCR (for BANK_STATEMENT)
     * @param statementNumber Statement number from OCR (for BANK_STATEMENT)
     * @param identifier Fallback description or identifier
     * @return Standardized filename
     */
    fun generateFilename(
        type: DocumentType,
        timestamp: LocalDateTime,
        contentHash: String,
        vendorName: String? = null,
        invoiceNumber: String? = null,
        bankName: String? = null,
        statementNumber: String? = null,
        identifier: String? = null,
        mediaType: String? = null,
    ): String {
        val date = "${timestamp.year}-${timestamp.month.number.toString().padStart(2, '0')}-${timestamp.day.toString().padStart(2, '0')}"
        val hash4 = contentHash.take(4)
        val ext = extensionFor(mediaType)

        return when (type) {
            DocumentType.INVOICE -> {
                val parts = mutableListOf("Rechnung")
                if (!vendorName.isNullOrBlank()) parts.add(sanitizeFilenamePart(vendorName))
                if (!invoiceNumber.isNullOrBlank()) parts.add(sanitizeFilenamePart(invoiceNumber))
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
            DocumentType.OUTGOING_INVOICE -> {
                // The invoice number is the identity here, not a vendor: we issued it, so the
                // number is ours and always present by the time the file is written.
                val parts = mutableListOf("Ausgangsrechnung")
                if (!invoiceNumber.isNullOrBlank()) {
                    parts.add(sanitizeFilenamePart(invoiceNumber))
                } else if (!identifier.isNullOrBlank()) {
                    parts.add(sanitizeFilenamePart(identifier))
                }
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
            DocumentType.BANK_STATEMENT -> {
                val parts = mutableListOf("Kontoauszug")
                if (!bankName.isNullOrBlank()) parts.add(sanitizeFilenamePart(bankName))
                if (!statementNumber.isNullOrBlank()) parts.add("Nr${sanitizeFilenamePart(statementNumber)}")
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
            DocumentType.CONTRACTS -> {
                val parts = mutableListOf("Vertrag")
                if (!identifier.isNullOrBlank()) parts.add(sanitizeFilenamePart(identifier))
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
            DocumentType.EIGENBELEG -> {
                val parts = mutableListOf("Eigenbeleg")
                if (!identifier.isNullOrBlank()) parts.add(sanitizeFilenamePart(identifier))
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
            DocumentType.ZBON_REPORT -> {
                val parts = mutableListOf("ZBon")
                if (!identifier.isNullOrBlank()) parts.add(sanitizeFilenamePart(identifier))
                parts.add(date)
                parts.add(hash4)
                parts.joinToString("_") + ".$ext"
            }
        }
    }

    /**
     * Sanitizes a single part of a filename (e.g., vendor name, invoice number).
     *
     * Removes special characters, limits length, and replaces spaces with hyphens.
     *
     * @param part Raw filename part
     * @param maxLength Maximum character length for this part
     * @return Sanitized filename part
     */
    private fun sanitizeFilenamePart(
        part: String,
        maxLength: Int = 30,
    ): String =
        part
            .replace(Regex("[^a-zA-Z0-9äöüÄÖÜß\\-\\s]"), "")
            .replace(Regex("\\s+"), "-")
            .take(maxLength)
            .trimEnd('-')

    /**
     * Gets the month folder name from a timestamp.
     *
     * @param timestamp Date timestamp
     * @return Month folder name in YYYY-MM format (e.g., "2025-01")
     */
    fun getMonthFolder(timestamp: LocalDateTime): String {
        val year = timestamp.year
        val month =
            timestamp.month.number
                .toString()
                .padStart(2, '0')
        return "$year-$month"
    }

    /**
     * Gets the type folder name for a PDF document type.
     *
     * Uses the extension function from DocumentTypeDisplay for consistency.
     * Folder names are German with ASCII-safe characters for cross-platform compatibility.
     *
     * @param type PDF document type
     * @return Type folder name (e.g., "ZBON", "RECHNUNG", "KONTO-AUSZUEGE")
     */
    fun getTypeFolder(type: DocumentType): String = type.getFolderName()

    /**
     * Gets the type folder name for an additional (non-enum) document type.
     *
     * Uses the type name in uppercase as the folder name.
     *
     * @param typeName Additional document type name (e.g., "VORLAGE")
     * @return Folder name in uppercase (e.g., "VORLAGE")
     */
    fun getTypeFolder(typeName: String): String = typeName.uppercase()

    /**
     * Generates a standardized filename for an additional (non-enum) document type.
     *
     * Filename pattern: {typeName_lowercase}_{YYYY-MM-DD}_{identifier}.pdf
     *
     * @param typeName Document type name (e.g., "VORLAGE")
     * @param timestamp Upload/generation timestamp
     * @param identifier Optional unique identifier
     * @return Standardized filename
     */
    fun generateFilename(
        typeName: String,
        timestamp: LocalDateTime,
        identifier: String?,
    ): String {
        val date = "${timestamp.year}-${timestamp.month.number.toString().padStart(2, '0')}-${timestamp.day.toString().padStart(2, '0')}"
        val sanitizedId = if (identifier.isNullOrBlank()) "" else "_" + sanitizeFilename(identifier)
        return "${typeName.lowercase()}_${date}$sanitizedId.pdf"
    }

    /**
     * Generates the complete file path for an additional (non-enum) document type.
     *
     * Path structure: {rootPath}/{TYPE_NAME}/{YYYY-MM}/filename.pdf
     *
     * @param rootPath Base storage directory path
     * @param typeName Document type name (e.g., "VORLAGE")
     * @param timestamp Upload/generation timestamp
     * @param filename Filename (should already be sanitized)
     * @return Complete file path as String
     */
    fun generateFilePath(
        rootPath: String,
        typeName: String,
        timestamp: LocalDateTime,
        filename: String,
    ): String {
        val typeFolder = getTypeFolder(typeName)
        val monthFolder = getMonthFolder(timestamp)
        return "$rootPath/$typeFolder/$monthFolder/$filename"
    }

    /**
     * Ensures the month directory exists for an additional (non-enum) document type.
     *
     * Creates the directory structure if it doesn't exist:
     * {rootPath}/{TYPE_NAME}/{YYYY-MM}/
     *
     * @param rootPath Base storage directory path
     * @param typeName Document type name (e.g., "VORLAGE")
     * @param timestamp Upload/generation timestamp
     * @return The created/existing directory as File object
     * @throws IllegalStateException if directory creation fails
     */
    fun ensureMonthDirectoryExists(
        rootPath: String,
        typeName: String,
        timestamp: LocalDateTime,
    ): File {
        val typeFolder = getTypeFolder(typeName)
        val monthFolder = getMonthFolder(timestamp)
        val directory = File("$rootPath/$typeFolder/$monthFolder")

        if (!directory.exists()) {
            val created = directory.mkdirs()
            if (!created) {
                throw IllegalStateException("Failed to create directory: ${directory.absolutePath}")
            }
            logger.info("Created month directory: ${directory.absolutePath}")
        } else {
            logger.debug("Month directory already exists: ${directory.absolutePath}")
        }

        return directory
    }

    /**
     * Generates the complete file path for a PDF document.
     *
     * Path structure: {rootPath}/{type}/{YYYY-MM}/filename.pdf
     *
     * @param rootPath Base storage directory path
     * @param type PDF document type
     * @param timestamp Upload/generation timestamp
     * @param filename Filename (should already be sanitized)
     * @return Complete file path as String
     */
    fun generateFilePath(
        rootPath: String,
        type: DocumentType,
        timestamp: LocalDateTime,
        filename: String,
    ): String {
        val typeFolder = getTypeFolder(type)
        val monthFolder = getMonthFolder(timestamp)
        return "$rootPath/$typeFolder/$monthFolder/$filename"
    }

    /**
     * Generates a relative file path for database storage.
     *
     * Path structure: {type}/{YYYY-MM}/filename.pdf
     *
     * @param type PDF document type
     * @param timestamp Upload/generation timestamp
     * @param filename Filename (should already be sanitized)
     * @return Relative file path (no leading slash)
     */
    fun generateRelativePath(
        type: DocumentType,
        timestamp: LocalDateTime,
        filename: String,
    ): String {
        val typeFolder = getTypeFolder(type)
        val monthFolder = getMonthFolder(timestamp)
        return "$typeFolder/$monthFolder/$filename"
    }

    /**
     * Generates a relative file path for database storage using a type name string.
     *
     * Path structure: {TYPE_NAME}/{YYYY-MM}/filename.pdf
     *
     * @param typeName Document type name (e.g., "VORLAGE")
     * @param timestamp Upload/generation timestamp
     * @param filename Filename (should already be sanitized)
     * @return Relative file path (no leading slash)
     */
    fun generateRelativePath(
        typeName: String,
        timestamp: LocalDateTime,
        filename: String,
    ): String {
        val typeFolder = getTypeFolder(typeName)
        val monthFolder = getMonthFolder(timestamp)
        return "$typeFolder/$monthFolder/$filename"
    }

    /**
     * Resolves a stored file path (relative or absolute) to an absolute path.
     *
     * Supports backward compatibility: if the path is already absolute, returns it as-is.
     * Otherwise, prepends the storage root path.
     *
     * @param storedPath The path as stored in the database
     * @param rootPath The current document storage root path
     * @return Absolute file path
     */
    fun resolveStoredPath(
        storedPath: String,
        rootPath: String,
    ): String =
        if (File(storedPath).isAbsolute) {
            storedPath
        } else {
            "$rootPath/$storedPath"
        }

    /**
     * Ensures the month directory exists for a given type and timestamp.
     *
     * Creates the directory structure if it doesn't exist:
     * {rootPath}/{type}/{YYYY-MM}/
     *
     * @param rootPath Base storage directory path
     * @param type PDF document type
     * @param timestamp Upload/generation timestamp
     * @return The created/existing directory as File object
     * @throws IllegalStateException if directory creation fails
     */
    fun ensureMonthDirectoryExists(
        rootPath: String,
        type: DocumentType,
        timestamp: LocalDateTime,
    ): File {
        val typeFolder = getTypeFolder(type)
        val monthFolder = getMonthFolder(timestamp)
        val directory = File("$rootPath/$typeFolder/$monthFolder")

        if (!directory.exists()) {
            val created = directory.mkdirs()
            if (!created) {
                throw IllegalStateException("Failed to create directory: ${directory.absolutePath}")
            }
            logger.info("Created month directory: ${directory.absolutePath}")
        } else {
            logger.debug("Month directory already exists: ${directory.absolutePath}")
        }

        return directory
    }

    /**
     * Validates that a file path is within the allowed storage root.
     *
     * Prevents path traversal attacks by ensuring the resolved path
     * is within the storage root directory.
     *
     * @param filePath Path to validate
     * @param rootPath Base storage directory path
     * @return true if path is valid and within root, false otherwise
     */
    fun isPathSafe(
        filePath: String,
        rootPath: String,
    ): Boolean =
        try {
            val file = File(filePath).canonicalFile
            val root = File(rootPath).canonicalFile
            file.path.startsWith(root.path)
        } catch (e: Exception) {
            logger.warn("Path validation failed for: $filePath", e)
            false
        }

    /**
     * Validates file size against maximum allowed size.
     *
     * @param fileSizeBytes File size in bytes
     * @param maxSizeMb Maximum allowed size in megabytes
     * @return true if file size is within limit, false otherwise
     */
    fun isFileSizeValid(
        fileSizeBytes: Long,
        maxSizeMb: Int,
    ): Boolean {
        val maxSizeBytes = maxSizeMb * 1024 * 1024L
        return fileSizeBytes <= maxSizeBytes
    }

    /**
     * Calculates SHA-256 hash of a file for deduplication and integrity.
     *
     * @param file File to hash
     * @return Hexadecimal string representation of SHA-256 hash
     * @throws Exception if file cannot be read or hashed
     */
    fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Formats file size to human-readable format.
     *
     * @param bytes File size in bytes
     * @return Human-readable file size (e.g., "45.6 KB", "1.2 MB")
     */
    fun formatFileSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }

    /**
     * File extension for a detected media type, defaulting to `pdf`.
     *
     * `.xml` uploads have been accepted since the e-invoice parsing slice, but every arm here
     * appended `.pdf` regardless, so an XRechnung was filed under a name no PDF reader can open
     * while the document row correctly recorded `application/xml` (E2E-2026-09-20 #7). For an
     * XRechnung the XML *is* the legally binding original.
     *
     * An unknown or absent media type falls back to `pdf` rather than inventing an extension:
     * every existing caller omits it and really does write a PDF, so their names are unchanged.
     */
    fun extensionFor(mediaType: String?): String =
        when (mediaType?.lowercase()?.substringBefore(';')?.trim()) {
            "application/xml", "text/xml" -> "xml"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            MEDIA_TYPE_MT940 -> "sta"
            else -> "pdf"
        }

    /**
     * Validates a file by checking magic bytes for supported formats.
     *
     * Supported formats:
     * - PDF: magic bytes `%PDF` (25 50 44 46)
     * - JPEG: magic bytes `FF D8 FF`
     * - PNG: magic bytes `89 50 4E 47`
     * - XML: an `<?xml` declaration, optionally behind a UTF-8 BOM
     * - MT940: the SWIFT tag structure, see [looksLikeMt940]
     *
     * XML is accepted because an XRechnung arrives as a bare XML file — it is the mandated German
     * B2G invoice format and has no PDF wrapper at all. Rejecting it here would leave the e-invoice
     * parser's XML path unreachable in production, since this gate runs before any parsing. MT940
     * is accepted for the same reason on the bank-statement side.
     *
     * @param file File to validate
     * @param declaredMimeType Optional MIME type declared in upload (for additional validation)
     * @return true if file is a valid supported format, false otherwise
     */
    fun isValidFile(
        file: File,
        declaredMimeType: String? = null,
    ): Boolean {
        val validMimeTypes =
            listOf("application/pdf", "image/jpeg", "image/png", "application/xml", "text/xml", MEDIA_TYPE_MT940)

        // If declared MIME type is provided, check it first
        if (declaredMimeType != null && declaredMimeType !in validMimeTypes) {
            return false
        }

        // Check magic bytes for supported formats
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(8)
                val bytesRead = input.read(header)
                if (bytesRead < 4) return false

                // PDF: %PDF (25 50 44 46)
                if (header[0] == 0x25.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x44.toByte() &&
                    header[3] == 0x46.toByte()
                ) {
                    return true
                }

                // JPEG: FF D8 FF
                if (bytesRead >= 3 &&
                    header[0] == 0xFF.toByte() &&
                    header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte()
                ) {
                    return true
                }

                // PNG: 89 50 4E 47
                if (bytesRead >= 4 &&
                    header[0] == 0x89.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() &&
                    header[3] == 0x47.toByte()
                ) {
                    return true
                }

                if (startsWithXmlDeclaration(header.copyOf(bytesRead))) return true

                // MT940 needs more than the 8-byte header: the second tag can sit 40 bytes in. The
                // re-read is deliberately last, so the common PDF/JPEG/PNG/XML path still costs
                // only the header.
                return looksLikeMt940(file.readBytes())
            }
        } catch (e: Exception) {
            logger.warn("Failed to read file magic bytes: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * True when [bytes] opens with an `<?xml` declaration, tolerating a leading UTF-8 BOM.
     *
     * Deliberately narrower than "starts with `<`": that would admit HTML, and the accepted-upload
     * set is a security boundary, not a convenience. An XRechnung always carries the declaration —
     * every instance in the corpus does — so the stricter test costs nothing real.
     */
    fun startsWithXmlDeclaration(bytes: ByteArray): Boolean {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val start = if (bytes.size >= 3 && bytes.copyOf(3).contentEquals(utf8Bom)) 3 else 0
        val declaration = "<?xml".toByteArray(Charsets.US_ASCII)
        if (bytes.size - start < declaration.size) return false
        return bytes.copyOfRange(start, start + declaration.size).contentEquals(declaration)
    }

    /**
     * Recognises a SWIFT MT940 statement from its tag structure.
     *
     * MT940 is plain text with no magic bytes, so without a content sniff a `.sta` download is
     * rejected as an unsupported type and the bank-statement parser is unreachable in production —
     * the same reason a bare XRechnung needed the XML declaration accepted here.
     *
     * The check is deliberately narrow: `:20:` must be the first tag, at a line start, and at least
     * one further tag must follow. That admits statements while still rejecting arbitrary text, so
     * the gate does not widen to "any file that happens to be readable".
     *
     * `:accounting-banking`'s `BankStatementFormatDetector` carries a similar shape, and the duplication is
     * accepted: this gate runs *before* any parsing and must not couple a general file-validation
     * utility to a parser module. The two answer different questions — "may this be stored at all"
     * versus "which parser reads it" — and are free to diverge.
     */
    fun looksLikeMt940(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val body = head.substringAfter("{4:", head)
        val tags =
            MT940_TAG
                .findAll(body)
                .map { it.groupValues[1] }
                .take(3)
                .toList()
        return tags.firstOrNull() == "20" && tags.size >= 2
    }

    /** A SWIFT tag at the start of a line, e.g. `:20:`, `:60F:`. */
    private val MT940_TAG = Regex("""(?m)^:(\d{2}[A-Z]?):""")

    /**
     * Returns the appropriate file extension for a given media type.
     *
     * @param mediaType MIME type string (e.g., "application/pdf", "image/jpeg")
     * @return File extension including dot (e.g., ".pdf", ".jpg", ".png")
     */
    fun getExtensionForMediaType(mediaType: String): String =
        when (mediaType) {
            "application/pdf" -> ".pdf"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            else -> ".bin"
        }

    /**
     * Soft deletes a PDF file by moving it to the .trash directory.
     *
     * The file is moved to a .trash directory while preserving the original directory structure.
     * This allows for easy restoration if needed.
     *
     * Trash structure: {rootPath}/.trash/{type}/{YYYY-MM}/filename_{timestamp}.pdf
     *
     * @param filePath Path to file to delete (soft delete)
     * @param rootPath Base storage directory path
     * @return true if file was moved to trash successfully, false otherwise
     */
    fun deleteFile(
        filePath: String,
        rootPath: String,
    ): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                logger.warn("File not found for deletion: $filePath")
                return false
            }

            // Ensure path is safe
            if (!isPathSafe(filePath, rootPath)) {
                logger.error("Unsafe file path for deletion: $filePath")
                return false
            }

            // Calculate relative path from root to preserve directory structure
            val rootFile = File(rootPath).canonicalFile
            val sourceFile = file.canonicalFile
            val relativePath = sourceFile.path.removePrefix(rootFile.path).removePrefix("/")

            // Create trash directory structure
            val trashPath = "$rootPath/.trash/$relativePath"
            val trashFile = File(trashPath)
            val trashDir = trashFile.parentFile

            // Ensure trash directory exists
            if (!trashDir.exists()) {
                val created = trashDir.mkdirs()
                if (!created) {
                    logger.error("Failed to create trash directory: ${trashDir.absolutePath}")
                    return false
                }
            }

            // If file already exists in trash, append timestamp to avoid overwrite
            val finalTrashFile =
                if (trashFile.exists()) {
                    val timestamp = System.currentTimeMillis()
                    val nameWithoutExt = trashFile.nameWithoutExtension
                    val extension = trashFile.extension
                    File(trashDir, "${nameWithoutExt}_$timestamp.$extension")
                } else {
                    trashFile
                }

            // Move file to trash
            val moved = file.renameTo(finalTrashFile)
            if (moved) {
                logger.info("Moved file to trash: $filePath -> ${finalTrashFile.absolutePath}")
            } else {
                logger.warn("Failed to move file to trash: $filePath")
            }
            moved
        } catch (e: Exception) {
            logger.error("Error moving file to trash: $filePath", e)
            false
        }
    }

    /**
     * Restores a file from the trash directory back to its original location.
     *
     * @param trashedFilePath Path to file in trash directory
     * @param rootPath Base storage directory path
     * @return true if file was restored successfully, false otherwise
     */
    fun restoreFile(
        trashedFilePath: String,
        rootPath: String,
    ): Boolean {
        return try {
            val trashedFile = File(trashedFilePath)
            if (!trashedFile.exists()) {
                logger.warn("Trashed file not found for restoration: $trashedFilePath")
                return false
            }

            // Ensure the trashed file is actually in the trash directory
            val trashRoot = File("$rootPath/.trash").canonicalFile
            val trashedFileCanonical = trashedFile.canonicalFile
            if (!trashedFileCanonical.path.startsWith(trashRoot.path)) {
                logger.error("File is not in trash directory: $trashedFilePath")
                return false
            }

            // Calculate original path by removing .trash from the path
            val relativePath =
                trashedFileCanonical.path
                    .removePrefix(trashRoot.path)
                    .removePrefix("/")

            // Handle files with timestamp suffix (appended during deletion if file existed)
            val cleanedRelativePath =
                if (relativePath.contains("_") && relativePath.lastIndexOf("_") > 0) {
                    val lastUnderscoreIndex = relativePath.lastIndexOf("_")
                    val beforeUnderscore = relativePath.substring(0, lastUnderscoreIndex)
                    val afterUnderscore = relativePath.substring(lastUnderscoreIndex + 1)

                    // Check if what's after underscore looks like a timestamp (digits before extension)
                    val extension = afterUnderscore.substringAfterLast(".")
                    val possibleTimestamp = afterUnderscore.substringBeforeLast(".")

                    if (possibleTimestamp.all { it.isDigit() } && possibleTimestamp.length >= 13) {
                        // This looks like a timestamp, remove it
                        "$beforeUnderscore.$extension"
                    } else {
                        // Not a timestamp, keep original name
                        relativePath
                    }
                } else {
                    relativePath
                }

            val restoredPath = "$rootPath/$cleanedRelativePath"
            val restoredFile = File(restoredPath)

            // Ensure parent directory exists
            val parentDir = restoredFile.parentFile
            if (!parentDir.exists()) {
                val created = parentDir.mkdirs()
                if (!created) {
                    logger.error("Failed to create parent directory for restoration: ${parentDir.absolutePath}")
                    return false
                }
            }

            // If file already exists at destination, don't overwrite
            if (restoredFile.exists()) {
                logger.warn("File already exists at restoration target, aborting restore: $restoredPath")
                return false
            }

            // Move file from trash back to original location
            val moved = trashedFile.renameTo(restoredFile)
            if (moved) {
                logger.info("Restored file from trash: $trashedFilePath -> ${restoredFile.absolutePath}")
            } else {
                logger.warn("Failed to restore file from trash: $trashedFilePath")
            }
            moved
        } catch (e: Exception) {
            logger.error("Error restoring file from trash: $trashedFilePath", e)
            false
        }
    }

    /**
     * Permanently deletes a file from the trash directory.
     *
     * This is an irreversible operation.
     *
     * @param trashedFilePath Path to file in trash directory
     * @param rootPath Base storage directory path
     * @return true if file was permanently deleted, false otherwise
     */
    fun permanentlyDeleteFile(
        trashedFilePath: String,
        rootPath: String,
    ): Boolean {
        return try {
            val trashedFile = File(trashedFilePath)

            // Ensure the file is actually in the trash directory
            val trashRoot = File("$rootPath/.trash").canonicalFile
            val trashedFileCanonical = trashedFile.canonicalFile
            if (!trashedFileCanonical.path.startsWith(trashRoot.path)) {
                logger.error("File is not in trash directory, refusing to delete: $trashedFilePath")
                return false
            }

            if (trashedFile.exists()) {
                val deleted = trashedFile.delete()
                if (deleted) {
                    logger.info("Permanently deleted file from trash: $trashedFilePath")
                } else {
                    logger.warn("Failed to permanently delete file from trash: $trashedFilePath")
                }
                deleted
            } else {
                logger.warn("Trashed file not found for permanent deletion: $trashedFilePath")
                false
            }
        } catch (e: Exception) {
            logger.error("Error permanently deleting file from trash: $trashedFilePath", e)
            false
        }
    }

    /**
     * Lists all files in the trash directory.
     *
     * @param rootPath Base storage directory path
     * @return List of files in trash directory
     */
    fun listTrashedFiles(rootPath: String): List<File> =
        try {
            val trashDir = File("$rootPath/.trash")
            if (!trashDir.exists()) {
                emptyList()
            } else {
                val supportedExtensions = setOf("pdf", "jpg", "jpeg", "png")
                trashDir
                    .walk()
                    .filter { it.isFile && it.extension.lowercase() in supportedExtensions }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Error listing trashed files", e)
            emptyList()
        }

    /**
     * Empties the entire trash directory.
     *
     * WARNING: This permanently deletes all files in trash. Use with caution.
     *
     * @param rootPath Base storage directory path
     * @return Number of files permanently deleted
     */
    fun emptyTrash(rootPath: String): Int =
        try {
            val trashDir = File("$rootPath/.trash")
            if (!trashDir.exists()) {
                0
            } else {
                var deletedCount = 0
                trashDir
                    .walk()
                    .filter { it.isFile }
                    .forEach { file ->
                        if (file.delete()) {
                            deletedCount++
                            logger.debug("Permanently deleted from trash: ${file.absolutePath}")
                        }
                    }
                logger.info("Emptied trash directory: $deletedCount files deleted")
                deletedCount
            }
        } catch (e: Exception) {
            logger.error("Error emptying trash directory", e)
            0
        }
}
