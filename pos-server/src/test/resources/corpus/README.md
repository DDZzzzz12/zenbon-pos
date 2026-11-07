# Parser corpus — duplicated copies

These files are **byte-identical duplicates** of fixtures in
[`accounting/einvoice/src/test/resources/corpus/`](../../../../../accounting/einvoice/src/test/resources/corpus/README.md)
and [`accounting/banking/src/test/resources/corpus/`](../../../../../accounting/banking/src/test/resources/corpus/README.md).
They are copied rather than read across module boundaries because a test resource is loaded from the
consuming module's classpath; reaching into another module's `src/test/resources` by relative path
breaks the moment either module's layout changes.

`EInvoiceUploadIntegrationTest`, `BankStatementUploadIntegrationTest` and `Mt940UploadGateTest` upload them through
`POST /api/documents/upload` to prove the parsers are wired into the upload path.

| File | Used for |
|---|---|
| `xrechnung-ubl.xml` | a bare XRechnung UBL invoice — the XML upload path |
| `zugferd-sample.pdf` | a ZUGFeRD PDF with an embedded `factur-x.xml` — the PDF-attachment path |
| `plain-scan.pdf` | a PDF with no attachment — the everyday scanned-invoice case |
| `malformed.xml` | deliberately broken XML — the parse-failure path |
| `camt053-statement.xml` | a real German DK `camt.053.001.02` with ten entries — the bank-statement path (copy of `:accounting-banking`'s `real-camt053-germany-dk.xml`) |
| `mt940-statement.sta` | a four-entry MT940 whose balances reconcile — the `.sta` upload gate (copy of `:accounting-banking`'s hand-authored `sparkasse-mt940.sta`) |

**Provenance and licensing are recorded once, in the `:accounting-einvoice` and `:accounting-banking` READMEs linked
above** — `camt053-statement.xml` comes from moiristo/camt (MIT, © 2014 Reinier de Lange) — do not
duplicate that record here, or the two will drift. `xrechnung-ubl.xml` is Apache-2.0 material from
the KoSIT XRechnung test suite and travels with `LICENSE-Apache-2.0.txt`, also copied here so the
licence text sits beside the file it covers. The other three are hand-authored in this repository.

If a fixture changes upstream, change it in `:accounting-einvoice` or `:accounting-banking` first and re-copy it here.
