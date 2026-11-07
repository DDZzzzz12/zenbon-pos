package org.example.project.pdf.zugferd

import org.example.project.pdf.template.GermanBusinessLetterTemplate
import org.openpdf.text.Font
import org.openpdf.text.pdf.BaseFont
import org.openpdf.text.pdf.PdfArray
import org.openpdf.text.pdf.PdfFileSpecification
import org.openpdf.text.pdf.PdfName
import org.openpdf.text.pdf.PdfWriter
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile

/**
 * Turns the plain business-letter [PdfWriter] into a ZUGFeRD/Factur-X container: a PDF/A-3-shaped
 * document that a human reads and a machine books from the same file.
 *
 * OpenPDF 3.0.5 ships **no** `PdfAWriter`, so this is hand-built on the plain writer from the
 * primitives it does expose — an sRGB OutputIntent, an embedded `factur-x.xml`, the catalog `/AF`
 * array, and a ZUGFeRD XMP packet. That is the sanctioned approach (spec decision C): the machine
 * payload is proven present and parseable by round-tripping the produced PDF back through the
 * `:accounting-einvoice` parser, while **strict** PDF/A-3 conformance (fonts/transparency/colour) is a manual
 * veraPDF gate, not something this packager can assert on its own.
 *
 * All fonts are embedded because PDF/A forbids the non-embedded base-14 Helvetica the plain letter
 * uses — see [embeddedLetterFonts].
 */
object ZugferdPackager {
    /** The conventional Factur-X attachment name; the parser looks for exactly this first. */
    const val FACTUR_X_FILENAME: String = "factur-x.xml"

    private const val REGULAR_FONT = "/fonts/inter_regular.ttf"
    private const val BOLD_FONT = "/fonts/inter_bold.ttf"

    /** The ZUGFeRD XMP extension-schema namespace and its properties. */
    private const val FX_NAMESPACE = "urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#"

    /**
     * A fully embedded font set mirroring [GermanBusinessLetterTemplate.LetterFonts]'s sizes, drawn
     * from the bundled OFL-licensed Inter TTFs. Bold weights use the real bold face rather than a
     * synthesised one so the glyphs are genuinely embedded.
     */
    fun embeddedLetterFonts(): GermanBusinessLetterTemplate.LetterFonts {
        val regular = embeddedBaseFont(REGULAR_FONT)
        val bold = embeddedBaseFont(BOLD_FONT)
        return GermanBusinessLetterTemplate.LetterFonts(
            header = Font(bold, 14f),
            bold = Font(bold, 11f),
            regular = Font(regular, 10f),
            small = Font(regular, 8f),
            tiny = Font(regular, 7f),
        )
    }

    /**
     * The pre-open step: raise the PDF version to 1.7 (PDF/A-3 is built on PDF 1.7). This has to run
     * before `document.open()` because the version is written into the file header there.
     */
    fun configureBeforeOpen(): (PdfWriter) -> Unit =
        { writer ->
            writer.setPdfVersion(PdfWriter.VERSION_1_7)
        }

    /**
     * The post-open step: set the sRGB OutputIntent, embed the CII as `factur-x.xml`, wire it into
     * the catalog `/AF` array, and write the ZUGFeRD XMP. Both the OutputIntent and the embedded-file
     * stream write into the writer body, which only exists once `document.open()` has run.
     *
     * @param ciiXml the EN 16931 CII produced upstream; embedded verbatim, never re-derived.
     */
    fun configureAfterOpen(
        ciiXml: ByteArray,
        title: String,
        author: String,
    ): (PdfWriter) -> Unit =
        { writer ->
            setSrgbOutputIntent(writer)
            embedFacturX(writer, ciiXml)
            writer.setXmpMetadata(zugferdXmp(title, author).toByteArray(Charsets.UTF_8))
        }

    /** PDF/A demands a device-independent colour target; the JVM's own sRGB profile is that target. */
    private fun setSrgbOutputIntent(writer: PdfWriter) {
        val srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB)
        writer.setOutputIntents(
            "Custom",
            null,
            "http://www.color.org",
            "sRGB IEC61966-2.1",
            srgb,
        )
    }

    /**
     * Embeds the CII stream as `factur-x.xml`, tags it as an alternative rendition of the invoice
     * (`AFRelationship = /Alternative`, as ZUGFeRD/Factur-X 2.x mandates for the embedded XML — a
     * strict receiver filtering on `Alternative` would otherwise ignore the payload), registers it in
     * the embedded-files name tree, and lists it in the catalog `/AF` array — the three places a
     * Factur-X reader looks.
     */
    private fun embedFacturX(
        writer: PdfWriter,
        ciiXml: ByteArray,
    ) {
        val fileSpec =
            PdfFileSpecification.fileEmbedded(
                writer,
                null,
                FACTUR_X_FILENAME,
                ciiXml,
                false,
                "text/xml",
                null,
            )
        fileSpec.put(PdfName("AFRelationship"), PdfName("Alternative"))
        writer.addFileAttachment(FACTUR_X_FILENAME, fileSpec)

        val associatedFiles = PdfArray()
        associatedFiles.add(fileSpec.reference)
        writer.extraCatalog.put(PdfName("AF"), associatedFiles)
    }

    private fun embeddedBaseFont(resource: String): BaseFont {
        val bytes =
            ZugferdPackager::class.java.getResourceAsStream(resource)?.readBytes()
                ?: error("bundled ZUGFeRD font missing on the classpath: $resource")
        return BaseFont.createFont(
            resource.substringAfterLast('/'),
            BaseFont.IDENTITY_H,
            BaseFont.EMBEDDED,
            true,
            bytes,
            null,
        )
    }

    /**
     * The XMP packet PDF/A-3 and ZUGFeRD require: `pdfaid` part 3 / conformance B, the Dublin Core /
     * Adobe basics, and the `fx:` Factur-X schema naming the embedded payload. Hand-built because
     * OpenPDF's [org.openpdf.text.xml.xmp.XmpWriter] carries no `fx` schema.
     */
    private fun zugferdXmp(
        title: String,
        author: String,
    ): String {
        val safeTitle = xmlEscape(title)
        val safeAuthor = xmlEscape(author)
        return """
            <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                  <pdfaid:part>3</pdfaid:part>
                  <pdfaid:conformance>B</pdfaid:conformance>
                </rdf:Description>
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$safeTitle</rdf:li></rdf:Alt></dc:title>
                  <dc:creator><rdf:Seq><rdf:li>$safeAuthor</rdf:li></rdf:Seq></dc:creator>
                </rdf:Description>
                <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
                  <pdf:Producer>OpenPDF</pdf:Producer>
                </rdf:Description>
                <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <xmp:CreatorTool>POS System Document Generator</xmp:CreatorTool>
                </rdf:Description>
                <rdf:Description rdf:about="" xmlns:fx="$FX_NAMESPACE">
                  <fx:DocumentType>INVOICE</fx:DocumentType>
                  <fx:DocumentFileName>$FACTUR_X_FILENAME</fx:DocumentFileName>
                  <fx:Version>1.0</fx:Version>
                  <fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
            """.trimIndent()
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
