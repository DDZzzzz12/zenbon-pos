package org.example.project.pdf.generator

import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.pdf.template.GermanBusinessLetterTemplate
import org.openpdf.text.Element
import org.openpdf.text.Paragraph
import org.openpdf.text.Phrase
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates a formal German business letter PDF.
 *
 * Creates a professional letter following DIN 676 standards with:
 * - Window envelope compatible layout
 * - Company header and recipient positioning
 * - Footer with contact information
 * - Custom letter content with paragraph formatting
 *
 * @param outputFile Target PDF file
 * @param title Letter title/reference
 * @param subject Letter subject line
 * @param recipientAddress Recipient address and details
 * @param shopInfo Shop/company information
 * @param letterContent Main letter body text (supports markdown-style formatting)
 * @param markdownText Enable basic markdown parsing for bold text
 */
fun generateLetter(
    outputFile: File,
    title: String,
    subject: String,
    recipientAddress: GermanBusinessLetterTemplate.RecipientAddress,
    shopInfo: ShopInfoDto.ShopInfoResponseDto,
    letterContent: String,
    markdownText: Boolean = false,
) {
    try {
        val template =
            GermanBusinessLetterTemplate(
                outputFile = outputFile,
                title = title,
                subject = subject,
                shopInfo = shopInfo,
                recipientAddress = recipientAddress,
            )

        template.generate { document, fonts ->

            // ========== DATE ==========
            // Format: "Berlin, 16. Januar 2026" (German style)
            val germanFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)
            val currentDate = LocalDate.now().format(germanFormatter)
            val cityName = shopInfo.shopCity ?: shopInfo.shopName
            val dateParagraph = Paragraph("$cityName, $currentDate", fonts.regular)
            dateParagraph.alignment = Element.ALIGN_RIGHT
            dateParagraph.spacingAfter = 20f
            document.add(dateParagraph)

            // ========== SUBJECT LINE ==========
            val subjectParagraph = Paragraph(subject, fonts.bold)
            subjectParagraph.spacingAfter = 15f
            document.add(subjectParagraph)

            // ========== LETTER CONTENT ==========
            // Split content by double newlines (paragraphs) or single newlines
            val paragraphs =
                if (letterContent.contains("\n\n")) {
                    letterContent.split("\n\n")
                } else {
                    letterContent.split("\n")
                }

            for (paragraphText in paragraphs) {
                if (paragraphText.isBlank()) continue

                // Process markdown if enabled
                if (markdownText) {
                    // Handle basic markdown: **bold**
                    val phrase = Phrase()
                    var remaining = paragraphText.trim()

                    while (remaining.isNotEmpty()) {
                        val boldStart = remaining.indexOf("**")

                        if (boldStart == -1) {
                            // No more bold text, add remaining as regular
                            phrase.add(Phrase(remaining, fonts.regular))
                            break
                        }

                        // Add text before bold
                        if (boldStart > 0) {
                            phrase.add(Phrase(remaining.substring(0, boldStart), fonts.regular))
                        }

                        // Find end of bold
                        val boldEnd = remaining.indexOf("**", boldStart + 2)
                        if (boldEnd == -1) {
                            // No closing **, treat as regular text
                            phrase.add(Phrase(remaining.substring(boldStart), fonts.regular))
                            break
                        }

                        // Add bold text
                        val boldText = remaining.substring(boldStart + 2, boldEnd)
                        phrase.add(Phrase(boldText, fonts.bold))

                        // Continue with remaining text
                        remaining = remaining.substring(boldEnd + 2)
                    }

                    val paragraph = Paragraph()
                    paragraph.add(phrase)
                    paragraph.spacingAfter = 10f
                    document.add(paragraph)
                } else {
                    // Simple paragraph without markdown processing
                    val paragraph = Paragraph(paragraphText.trim(), fonts.regular)
                    paragraph.spacingAfter = 10f
                    document.add(paragraph)
                }
            }

            // ========== CLOSING SPACING ==========
            document.add(Paragraph("\n", fonts.regular))

            // ========== SIGNATURE AREA ==========
            document.add(Paragraph("Mit freundlichen Grüßen", fonts.regular))
            document.add(Paragraph("\n\n", fonts.regular)) // Space for signature
            document.add(Paragraph(shopInfo.shopName, fonts.regular))
        }
    } catch (e: Exception) {
        throw e
    }
}
