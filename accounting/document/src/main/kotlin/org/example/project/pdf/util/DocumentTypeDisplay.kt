/**
 * Document Type Display Utility
 *
 * Provides JVM-specific display utilities for DocumentType enum.
 * Most functionality is now built into the enum itself in the shared module.
 * This file provides backward-compatible extension functions and Locale support.
 *
 * Usage:
 * ```kotlin
 * val type = DocumentType.INVOICE
 * val germanName = type.getDisplayName(Locale.GERMAN) // "RECHNUNG"
 * val englishName = type.getDisplayName(Locale.ENGLISH) // "Invoice"
 * val folderName = type.getFolderName() // "RECHNUNG"
 * ```
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.pdf.util

import org.example.project.core.domain.enums.document.DocumentType
import java.util.Locale

/**
 * Extension function to get the display name for a DocumentType using java.util.Locale.
 *
 * Returns localized display names based on the provided locale.
 * Defaults to German for unrecognized locales (primary business locale).
 *
 * @param locale The java.util.Locale for which to get the display name
 * @return Localized display name string
 */
fun DocumentType.getDisplayName(locale: Locale = Locale.GERMAN): String = getDisplayName(locale.language)

// ===== BACKWARD-COMPATIBLE EXTENSION FUNCTIONS =====
// These delegate to the enum's built-in properties for backward compatibility
// with existing code that uses extension function syntax.

/**
 * Gets the German display name for the document type.
 * @return German display name
 */
fun DocumentType.getGermanDisplayName(): String = this.displayNameDe

/**
 * Gets the English display name for the document type.
 * @return English display name
 */
fun DocumentType.getEnglishDisplayName(): String = this.displayNameEn

/**
 * Gets the folder name for organizing files of this document type.
 * @return Folder name string
 */
fun DocumentType.getFolderName(): String = this.folderName

/**
 * Gets the file prefix for generating filenames of this document type.
 * @return File prefix string (lowercase)
 */
fun DocumentType.getFilePrefix(): String = this.filePrefix
