package org.example.project.einvoice

import org.example.project.einvoice.check.EInvoiceInvariants
import org.example.project.einvoice.detect.EInvoicePayloadLocator
import org.example.project.einvoice.detect.EInvoiceSyntax
import org.example.project.einvoice.detect.EInvoiceSyntaxDetector
import org.example.project.einvoice.detect.ZugferdProfile
import org.example.project.einvoice.detect.ZugferdProfileDetector
import org.example.project.einvoice.model.EInvoice
import org.example.project.einvoice.model.EInvoiceFormat
import org.example.project.einvoice.syntax.CiiInvoiceMapper
import org.example.project.einvoice.syntax.UblInvoiceMapper
import org.example.project.securexml.SecureXml
import org.w3c.dom.Document

/**
 * Composes the module's stages into the [EInvoiceParser] boundary: locate the payload, parse it
 * safely, detect syntax and profile, map it, and check its arithmetic.
 *
 * **Every outcome is a value.** The bytes come from a supplier, so a broken file, an unrelated
 * attachment or a booking-aid profile are all expected conditions rather than exceptional ones —
 * and a caller holding an [EInvoiceParseResult] cannot forget to handle them the way it can forget
 * a `catch`. No exception escapes [parse].
 *
 * A class rather than an object because it is registered in DI.
 */
class DefaultEInvoiceParser : EInvoiceParser {
    override fun parse(
        bytes: ByteArray,
        mediaType: String,
    ): EInvoiceParseResult {
        val payload =
            attempt { EInvoicePayloadLocator.locate(bytes, mediaType) }
                .getOrElse { return failed("E-invoice payload could not be read from the $mediaType upload", it) }
                ?: return EInvoiceParseResult.NoEInvoiceFound

        val document =
            attempt { SecureXml.parse(payload) }
                .getOrElse { return failed("E-invoice XML payload could not be parsed", it) }

        // Not an e-invoice at all — an arbitrary XML attachment travels in PDFs all the time.
        val syntax = EInvoiceSyntaxDetector.detect(document) ?: return EInvoiceParseResult.NoEInvoiceFound

        val profile =
            attempt { zugferdProfile(document, syntax) }
                .getOrElse { return failed("ZUGFeRD profile could not be read from the CII payload", it) }
        nonConformance(profile)?.let { return it }

        val invoice =
            attempt { map(document, syntax) }
                .getOrElse { return failed("${syntax.name} payload could not be mapped", it) }

        val violation =
            attempt { EInvoiceInvariants.check(invoice) }
                .getOrElse { return failed("E-invoice totals could not be checked", it) }
        if (violation != null) {
            return EInvoiceParseResult.Failure("E-invoice totals are inconsistent: $violation")
        }

        return EInvoiceParseResult.Success(invoice.copy(format = formatOf(syntax, profile)))
    }

    /** Only CII carries a guideline URN; a UBL document has no ZUGFeRD profile to read. */
    private fun zugferdProfile(
        document: Document,
        syntax: EInvoiceSyntax,
    ): ZugferdProfile? = if (syntax == EInvoiceSyntax.CII) ZugferdProfileDetector.detect(document) else null

    /**
     * A null profile is not a refusal: a bare XRechnung declares no URN at all, and absence must
     * not be read as MINIMUM.
     */
    private fun nonConformance(profile: ZugferdProfile?): EInvoiceParseResult.NotConformant? {
        if (profile == null || profile.isEn16931Conformant) return null
        return EInvoiceParseResult.NotConformant(
            profile = profile.urn,
            reason =
                "ZUGFeRD profile ${profile.name} (${profile.urn}) carries booking data only " +
                    "and is not an EN 16931 invoice",
        )
    }

    private fun map(
        document: Document,
        syntax: EInvoiceSyntax,
    ): EInvoice =
        when (syntax) {
            EInvoiceSyntax.CII -> CiiInvoiceMapper.map(document)
            EInvoiceSyntax.UBL -> UblInvoiceMapper.map(document)
        }

    /**
     * The parser is authoritative about the format, because a mapper sees only one syntax and
     * cannot tell a ZUGFeRD CII payload from a CII-flavoured XRechnung. The guideline URN settles
     * it.
     */
    private fun formatOf(
        syntax: EInvoiceSyntax,
        profile: ZugferdProfile?,
    ): EInvoiceFormat =
        when {
            syntax == EInvoiceSyntax.UBL -> EInvoiceFormat.UBL
            profile == ZugferdProfile.XRECHNUNG -> EInvoiceFormat.XRECHNUNG
            else -> EInvoiceFormat.ZUGFERD
        }

    /**
     * Keeps the underlying message — `XmlSecurityException` names what the parser refused,
     * `EInvoiceMappingException` names the missing term's XPath, `XmlValueException` carries the
     * raw text that would not parse. An operator holding a rejected invoice needs exactly that;
     * "parse failed" tells them nothing.
     */
    private fun failed(
        stage: String,
        cause: Throwable,
    ): EInvoiceParseResult.Failure = EInvoiceParseResult.Failure("$stage: ${cause.message ?: cause::class.simpleName}", cause)

    /**
     * Runs one stage, converting any failure into a value.
     *
     * `Exception` is caught broadly rather than per type. Each stage already declares its own typed
     * refusal (`EInvoicePayloadException`, `XmlSecurityException`, `EInvoiceMappingException`,
     * `XmlValueException`) and those carry the actionable message, but the input is
     * supplier-controlled: a library defect on hostile bytes must not turn into an unhandled
     * exception at the boundary, and enumerating types would leave exactly that gap. The stage
     * prefix, not the exception class, is what tells the caller where it broke.
     *
     * `StackOverflowError` is caught alongside, and only it. It is the one `Error` a *document* can
     * cause — a pathologically nested DOM walked recursively — so it is really malformed input
     * wearing an `Error`'s clothes, and the stack is fully unwound by the time it is caught. Every
     * other `Error` (heap exhaustion, a linkage failure) says something about the JVM rather than
     * about this file; swallowing one would downgrade a process-level problem to a per-invoice
     * `Failure` and hide it. [org.example.project.securexml.SecureXml] draws the same line.
     */
    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: StackOverflowError) {
            Result.failure(e)
        }
}
