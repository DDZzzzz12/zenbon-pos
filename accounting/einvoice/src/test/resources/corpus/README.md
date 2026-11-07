# E-invoice test corpus — provenance and licensing

Every file in this directory is either **borrowed from an Apache-2.0 source** (recorded below with its
upstream commit and original path) or **written by us**. Nothing here comes from a source whose licence
we could not clear. The rejected sources are listed at the bottom **so nobody has to redo that
research** — and so nobody is tempted to "complete the set" from them.

> **Four of these files are duplicated into `pos-server/src/test/resources/corpus/`**
> (`xrechnung-ubl.xml`, `zugferd-sample.pdf`, `plain-scan.pdf`, `malformed.xml`, plus
> `LICENSE-Apache-2.0.txt`) for `EInvoiceUploadIntegrationTest`, because a test resource is read from
> the consuming module's own classpath. **This file remains the single provenance record** — the copy
> carries only a pointer back here. Change a fixture here first, then re-copy it there.

## Borrowed — KoSIT XRechnung test suite (Apache-2.0)

Upstream: <https://github.com/itplr-kosit/xrechnung-testsuite>
Licence: Apache-2.0 (`LICENSE` at repo root; the repo carries **no** `NOTICE` file).
Commit pinned: `3e5d609a6920e47bd5f26cb38565bc3d3b6d0459` ("fix 02.05 documentation", 2026-07-10).
Fetched with `curl` against `raw.githubusercontent.com` at that SHA (byte-faithful; no markdown
conversion in the path).

| File here | Original path upstream | Original filename |
|---|---|---|
| `xrechnung-cii.xml` | `src/test/business-cases/standard/` | `01.01a-INVOICE_uncefact.xml` |
| `xrechnung-ubl.xml` | `src/test/business-cases/standard/` | `01.01a-INVOICE_ubl.xml` |

Both are the *same* business case (01.01a) in the two XRechnung syntaxes — CII (UN/CEFACT
Cross Industry Invoice) and UBL — which makes them a useful pair for syntax-detection tests.

The files are **unmodified** apart from the filename. Apache-2.0 requires preserving copyright and
licence notices *within* the distributed work, not preserving filenames, so renaming is permitted;
the upstream names are recorded in the table above. A verbatim copy of the licence text is at
`LICENSE-Apache-2.0.txt` (also taken from the pinned commit).

## Hand-authored — our own work

These are original files written for this repository. No bytes from any third-party corpus were used.
They carry the same licence as the rest of this repository.

| File | What it is |
|---|---|
| `zugferd-sample.pdf` | PDF/A-3-style one-page PDF whose embedded file is named `factur-x.xml`, carrying a CII payload declaring profile `urn:cen.eu:en16931:2017` |
| `zugferd-minimum.pdf` | Same shape, but the CII payload declares `urn:factur-x.eu:1p0:minimum` (MINIMUM profile — parsers must be able to reject/degrade on it) |
| `plain-scan.pdf` | One-page PDF with **no** attachment; stands in for a scanned paper invoice |
| `malformed.xml` | Deliberately broken XML (unclosed `<ram:ID>` element) for the parse-failure path |
| `profile-basicwl.xml` | Bare CII document context declaring `urn:factur-x.eu:1p0:basicwl` (BASIC WL) |
| `profile-basic.xml` | Bare CII document context declaring `urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic` (BASIC) |
| `profile-extended.xml` | Bare CII document context declaring `urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended` (EXTENDED) |

The ZUGFeRD *format* is fair game to author against: FeRD's own licence explicitly grants
"further development, further processing" of the format. What is not fair game is copying FeRD's or
ZUGFeRD's **sample documents**, and we did not.

### How the PDFs were made

A throwaway PDFBox 3.0.7 generator (`TempCorpusGenerator.kt`) was written under
`accounting/einvoice/src/test/kotlin/`, run once, and **deleted** — only its output is committed. It is not
production code and was never intended to live in the module. To regenerate, re-author an equivalent
generator; the PDFs are stable inputs, not build artifacts.

Each PDF carries a single Helvetica page, an sRGB output intent, XMP with `pdfaid:part=3` /
`pdfaid:conformance=B` plus the Factur-X XMP extension block, and (for the two ZUGFeRD files) the XML
attached via `PDEmbeddedFilesNameTreeNode` on the catalog with `AFRelationship = /Data` and an `/AF`
array on the catalog.

> These are **structurally** PDF/A-3 shaped but have not been run through a PDF/A validator
> (veraPDF). They exist to exercise attachment extraction, not to certify PDF/A conformance. Do not
> cite them as evidence that our PDF *writing* is PDF/A-3 conformant.

### The authored CII payload

Kept minimal — only the terms a mapper reads. Numbers are internally consistent so the later
invariant check passes:

net `100.00` + 19 % VAT `19.00` = gross `119.00`, and the VAT group's `ram:CalculatedAmount` (`19.00`)
equals the header `ram:TaxTotalAmount` (`19.00`).

### The three `profile-*.xml` fixtures — what they prove, and what they do not

They are **regression guards, not evidence.** We wrote both the fixture and the constant it is
compared against, so a test passing proves only that the two agree with each other. If the URN in
`ZugferdProfile` were wrong, we would have written the same wrong string into the fixture and the
test would still be green. Do not cite them as independent confirmation of a URN.

What they *do* guard is that a later edit to `ZugferdProfile` cannot silently stop these three
profiles from resolving. That matters most for `BASIC_WL`: it is one of the two profiles the parser
**rejects** as non-conformant, so if its URN stopped matching, `fromUrn` would return null, the
conformance gate would never fire, and a booking aid would be accepted as a valid e-invoice.

The correctness of the URNs themselves rests on the research below, not on these files.

### Where the profile URNs were verified (2026-08-09)

All six URNs in `ZugferdProfile` are the ZUGFeRD 2.1+ / Factur-X 1.0.x spelling and were checked
byte-for-byte against three independent implementations, which agree exactly. Nothing was copied into
this repository — the sources were read, not vendored.

| Source | What it is | URL |
|---|---|---|
| Mustangproject `Profiles.java` | Java, Apache-2.0, the reference German ZUGFeRD library | <https://github.com/ZUGFeRD/mustangproject> (`library/src/main/java/org/mustangproject/ZUGFeRD/Profiles.java`) |
| ZUGFeRD-csharp `ZUGFeRD/Profile.cs` | C#, different author and codebase — a genuinely independent transcription | <https://github.com/stephanstapel/ZUGFeRD-csharp> |
| FacturX API URN reference | Third-party documentation enumerating the FNFE-MPE guideline IDs | <https://facturxapi.com/docs/urns-schemas-factur-x> |

Two things worth keeping in mind before editing the enum:

- **`#compliant#` and `#conformant#` are different words.** `BASIC` (and `XRECHNUNG`) are `#compliant#`
  — a CIUS, i.e. a *restriction* of EN 16931. `EXTENDED` is `#conformant#` — an *extension*.
  Transposing them is the easy mistake. It fails *quietly*: because the transposed string still starts
  with the EN 16931 base URN, the longest-prefix fallback degrades it to `EN16931` rather than returning
  null, so the invoice is still accepted and only the profile label is wrong. `ZugferdProfileDetectorTest`
  pins that behaviour in both directions.
- **Version drift is real.** ZUGFeRD 2.0 used a `urn:zugferd.de:2p0:*` infix (`urn:zugferd.de:2p0:basicwl`,
  `urn:cen.eu:en16931:2017#compliant#urn:zugferd.de:2p0:basic`, …). ZUGFeRD 2.1 (March 2020) replaced it
  with `urn:factur-x.eu:1p0:*`, and 2.2 / 2.3 / Factur-X 1.0.07+ kept that unchanged. We deliberately
  declare only the 2.1+ forms; the 2.0 forms are asserted *not* to resolve, so adding 2.0 support would
  have to be a deliberate decision.

## Discrepancies found against the real KoSIT CII instance

The CII paths originally handed to this task were transcribed from documentation and unverified. The
vendored KoSIT file is a real conformant instance, so where they disagree, **KoSIT wins**. Recorded
here because later mapper work depends on getting these right:

1. **The profile URN is not a bare value — do not match it by equality.** KoSIT's
   `GuidelineSpecifiedDocumentContextParameter/ram:ID` is
   `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0`, i.e. the EN 16931 URN with
   a `#compliant#` national-extension suffix. A parser must **prefix-match** (or split on `#`), or it
   will fail to recognise every real XRechnung. Our hand-authored sample deliberately uses the bare
   `urn:cen.eu:en16931:2017` so both shapes are covered by the corpus.
2. **`ram:ApplicableTradeTax` is ambiguous — it exists at two levels.** The header VAT breakdown lives
   at `rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeSettlement/ram:ApplicableTradeTax`.
   There is *also* a line-level `…/ram:IncludedSupplyChainTradeLineItem/ram:SpecifiedLineTradeSettlement/
   ram:ApplicableTradeTax`, which carries only `TypeCode`/`CategoryCode`/`RateApplicablePercent` and
   **no amounts**. Selecting `//ram:ApplicableTradeTax` unqualified will pick up the line-level ones and
   yield null basis/calculated amounts.
3. **Parent paths were omitted in the brief.** `ram:ApplicableHeaderTradeAgreement`,
   `ram:ApplicableHeaderTradeSettlement` and `ram:IncludedSupplyChainTradeLineItem` are all children of
   `rsm:SupplyChainTradeTransaction`, not of the document root. Likewise
   `ram:SpecifiedTradeSettlementHeaderMonetarySummation` sits under `ram:ApplicableHeaderTradeSettlement`.
4. **Seller VAT ID needs a `schemeID` filter.** It is at
   `ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID` but only when `@schemeID="VA"`. The same
   element with `@schemeID="FC"` carries the German *Steuernummer*, a different identifier. Taking the
   first `SpecifiedTaxRegistration/ram:ID` unconditionally will sometimes return a tax number as a VAT ID.
5. **VAT IDs in the wild contain whitespace.** KoSIT's value is `DE 123456789`, with a space. Any
   comparison or validation must normalise whitespace first.
6. **Amounts are not zero-padded to 2 decimals.** KoSIT writes `336.9`, not `336.90`, and
   `ram:RateApplicablePercent` as `7`, not `7.00`. Parse as an arbitrary-precision decimal and never
   compare money as strings.
7. **`currencyID` appears on only some amounts.** In the KoSIT instance only `ram:TaxTotalAmount`
   carries `currencyID="EUR"`; the sibling totals carry none. The document currency must be read from
   `ram:ApplicableHeaderTradeSettlement/ram:InvoiceCurrencyCode`, not inferred per-amount.
8. **`ram:LineID` is not numeric.** KoSIT uses free text (`Zeitschrift [...]`, `Porto + Versandkosten`)
   as the line ID. Do not parse it as an integer.
9. **Empty elements occur.** `<ram:ApplicableHeaderTradeDelivery/>` is present but empty — a mapper must
   tolerate the element existing with no children.

Everything else in the brief checked out: `rsm:ExchangedDocument/ram:ID` is the invoice number, and
`rsm:ExchangedDocument/ram:IssueDateTime/udt:DateTimeString` carries `format="102"` with a `YYYYMMDD`
value.

## Rejected sources — do NOT vendor from these

Settled by a prior licence-research pass. Do not re-open, and do not copy a single byte from any of
them, however convenient a file may look.

| Source | Why rejected |
|---|---|
| [ZUGFeRD/corpus](https://github.com/ZUGFeRD/corpus) | Carries an Apache header over content the project does not own. Contains **real** invoices (third-party personal/commercial data) plus AWV/FeRD and CEN (EUPL) material. The header cannot relicense what the uploader had no rights to. |
| FeRD ZUGFeRD 2.3 information package | Its Apache carve-out covers only the **schemata and Schematron**, not the sample documents, which are non-transferable. |
| CEN EN 16931 examples | **EUPL-1.2** — a copyleft licence incompatible with how we ship. |

If a future task needs more coverage, extend the KoSIT set (Apache-2.0, same pinned repo) or author
more fixtures ourselves. Those are the only two sanctioned routes.
