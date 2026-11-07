# Bank-statement corpus

Two kinds of file, kept visibly apart by the `real-` filename prefix:

- **Hand-authored** (no prefix), written from the format specifications (DK MT940 / DFÜ-Abkommen
  Anlage 3 for `:86:`, ISO 20022 camt.053) on 2026-08-12.
- **Real exports** (`real-`), vendored 2026-08-13 from permissively-licensed open-source test suites.
  See *Attribution* below — retaining the copyright notice is a licence condition, not a courtesy.

**Hand-authored fixtures are not a substitute for real bank exports.** They encode what their author
already knew, so they can only ever re-check the cases the parser was written against. The real files
proved the point immediately: within an hour of being added they found four defects that 99 unit
tests had not (see *What the real files caught*).

## Attribution

| Source | Licence | Files taken |
|---|---|---|
| [wolph/mt940](https://github.com/wolph/mt940) | BSD 3-Clause, © 2014 Rick van Hattem | `real-mt940-bil-luxembourg.sta`, `real-mt940-dk-subfields.sta`, `real-mt940-gvc-wrapped-tags.sta`, `real-mt940-raiffeisen-huf.sta`, `real-mt940-invalid-date.sta` |
| [moiristo/camt](https://github.com/moiristo/camt) | MIT, © 2014 Reinier de Lange | `real-camt053-germany-dk.xml` |

Both licences permit redistribution with the copyright notice retained; this table is that notice.
Each file's `.expected.json` repeats its own source and licence, so a file separated from this README
still carries its provenance.

**Personal-data scan.** Every real file was read end to end before being committed. They are the
upstream projects' own test fixtures and were already synthetic: account numbers of the form
`12345678/1020304050`, IBANs such as `DE99700500000000012345`, and counterparties that are either
placeholders (`Herr Ueberweisender`, `Glaeubigerfirma`, `NOM DU DO / BENEFICIAIRE 11`) or companies
rather than individuals (`PayPal (Europe) S.a.r.l.`, `Finanzamt Muenchen`). No natural person's name,
real IBAN or contactable detail was found. Nothing was rewritten, so the parser still sees exactly
what the upstream suite sees.

## Adding a real bank file

Drop it in this folder with a sibling `<name>.expected.json` and add a row to the table below. The
naming convention keeps the two kinds visibly apart:

| kind | filename prefix | README "Origin" column |
|---|---|---|
| hand-authored | none (as below) | `hand-authored, <date>` |
| real export | `real-` | the exact source, its licence, and who anonymised it |

A real file must additionally record, in this README:

1. **Source** — project or portal URL, and the commit/date it was taken from.
2. **Licence** — checked individually. Permissive licences need attribution here; a GPL project's
   fixtures must never be vendored, only read.
3. **Personal-data scan** — confirmation that the file was read end to end and that every real name,
   IBAN, account number and reference was replaced with a synthetic one of the same *shape* (IBAN
   length and check digits, `?32`/`?33` split positions), so the parser still sees what it saw.

Published samples are usually anonymised. "Usually" is not a standard to commit bank data on.

## What each file exercises

| File | Origin | Exercises | Expected |
|---|---|---|---|
| `sparkasse-mt940.sta` | hand-authored, 2026-08-12 | German savings-bank shape: `:20:`/`:25:` IBAN/`:28C:`/`:60F:`/`:62F:`, four `:61:`+`:86:` pairs mixing credits and debits, GVC 166 and 177, `?00` posting text, `EREF+`/`MREF+`/`CRED+`/`SVWZ+`, `?30`/`?31`, `?32`+`?33` split name. Four values wrap across a line boundary — a reference, an IBAN, a Gläubiger-ID and a name — so a rejoin that inserted a separator would break it. | `Success`, 4 entries, 1234.56 → 2394.16 |
| `volksbank-mt940-latin1.sta` | hand-authored, 2026-08-12 | The **ISO-8859-1** leg. Umlauts are single 0xFC/0xE4/0xF6 bytes, so strict UTF-8 decoding must fail and the fallback must run; a lenient decoder would put `U+FFFD` into the counterparty name. | `Success`, 3 entries, 980.00 → 1150.00, first counterparty `Müller Bäckerei GmbH` |
| `mt940-legacy-account.sta` | hand-authored, 2026-08-12 | `:25:` carrying `BLZ/Kontonummer` rather than an IBAN, and unstructured `:86:` fields with no `?` subfields at all. The whole `:86:` must become the remittance and every counterparty field must stay null — the decoder's all-or-nothing fallback. Also `:61:` lines with no `//` bank reference. | `Success`, 3 entries, `accountNumber` set, `accountIban` null |
| `mt940-multi-statement.sta` | hand-authored, 2026-08-12 | Three `:20:` blocks for consecutive days on one account, each day's `:62F:` equal to the next day's `:60F:`. Merges to one statement whose opening is day 1's and closing is day 3's, so the balance check still spans the whole file. | `Success`, 4 entries merged, 500.00 → 370.00 |
| `camt053-v02-german.xml` | hand-authored, 2026-08-12 | `camt.053.001.02`: `OPBD`/`CLBD`, `BkTxCd`, `RmtInf/Ustrd`, one credit (counterparty on the debtor side) and one debit (counterparty on the creditor side) — a mapper reading one side unconditionally gets half the statement wrong. | `Success`, 2 entries, 3200.00 → 3550.01 |
| `camt053-v08.xml` | hand-authored, 2026-08-12 | The `.08` namespace with the same content, written to the `.08` schema shape. Header, balances, entries, amounts, dates and IBANs all survive the version change. Exercises `Party40Choice` and `BICFI`, which the mapper originally could not read — see below. | `Success`, 2 entries, 3200.00 → 3550.01, counterparty names asserted |
| `camt053-batched-entry.xml` | hand-authored, 2026-08-12 | One `Ntry` with two `TxDtls` (a Sammelbuchung). A batch has no single counterparty, so the entry must carry the booked amount with every counterparty field null rather than the first `TxDtls`'s party. | `Success`, 1 entry, counterparty null |
| `xrechnung-not-a-statement.xml` | hand-authored, 2026-08-12 | A small UBL invoice. Guards mutual exclusivity with the e-invoice parser: only one of the two may claim a document, or both could write its processing status. | `NoStatementFound` |
| `real-camt053-germany-dk.xml` | moiristo/camt, MIT | A real German DK `camt.053.001.02`: `PRCD` opening balance, ten entries, SEPA `EndToEndId`/`MndtId`, a return entry with no counterparty. Self-validating — the invariant passes only if all ten entries sum to the stated closing balance. | `Success`, 10 entries, 112.72 → 158780.32 |
| `real-mt940-bil-luxembourg.sta` | wolph/mt940, BSD-3 | A bare `:28:`; `:61:` supplementary details on a second line that must not reach the bank reference; a trailing `-` followed by a fragment to ignore; `?60`–`?65`; a statement-level `:86:` after `:62F:`. Balance 16.40 − 5.00 = 11.40, checked by hand. | `Success`, 1 entry |
| `real-mt940-dk-subfields.sta` | wolph/mt940, BSD-3 | Real DK `:86:` — GVC 105, `?32`+`?33` split name, `?34`, `?60`–`?63` with an embedded `ABWA+`. | `Failure` — its own arithmetic does not reconcile |
| `real-mt940-gvc-wrapped-tags.sta` | wolph/mt940, BSD-3 | The strongest evidence for the no-separator rejoin: a real export splits `SVWZ+` across the `?24`/`?25` boundary as `S` + `VWZ+`, and `CRED+` across `?23`/`?24`. Any separator destroys both. | `Failure` — fragment, no `:62F:` |
| `real-mt940-raiffeisen-huf.sta` | wolph/mt940, BSD-3 | A non-EUR (HUF) statement with a Latin-2 payload, funds codes, no customer references, unstructured multi-line `:86:`. Why entries inherit the statement currency. | `Failure` — truncated export |
| `real-mt940-invalid-date.sta` | wolph/mt940, BSD-3 | A `:61:` value date of `160230` — 30 February. A clean refusal is correct; coercing it would book on a day the bank never stated. | `Failure` — invalid date |

## What the real files caught

Added 2026-08-13. Four defects, none of which 99 unit tests had found, because every one of them
needed an input nobody thought to invent:

1. **The SWIFT end-of-message marker corrupted the closing balance.** A lone `-` line was treated as
   a continuation and joined — with no separator, correctly for `:86:` — onto the last tag, turning
   `:62F:C170914EUR12345,98` into `12345,98-`. Four of six real files failed on it. Anything after
   the marker was also still parsed, so a trailing fragment became a bogus entry.
2. **Every entry of a non-EUR statement was wrong.** `:61:` carries no currency, and the parser
   hardcoded `EUR` instead of inheriting the statement's. On the HUF Raiffeisen export the currency
   invariant then rejected the whole file — no non-EUR statement could be stored at all.
3. **A hard-wrapped `:61:` could not be read.** A Sparkasse export breaks the statement line after
   the funds code (`:61:1811261126CR` / `30,00N062NONREF`). This is *not* the supplementary-details
   case — here the second line is the value — so the parser now tries the tag's own line first and
   falls back to the joined value.
4. **A bare `:28:` lost the statement number.** The Luxembourg BIL export omits the `C`, and
   reconciliation keys statement identity on that field.

Each has a regression test in `Mt940TokenizerTest` / `Mt940StatementParserTest`, so the fixtures are
belt and braces rather than the only guard.

Worth knowing: five of six real files are expected to **fail**, and that is not a parser weakness.
They are a parser test suite's fixtures, and wolph's parser performs no balance check, so its files
never had to reconcile. Two are truncated exports with no `:62F:`, two state a closing balance their
own entries do not reach, and one carries a 30 February value date. Refusing all five is the
behaviour the design asks for. If real *production* statements turn out to fail this often, that is
an argument to revisit D9 — these fixtures are not evidence either way.

## What this corpus already caught: camt.053.001.04 and later

`camt053-v08.xml` is written to the real `.08` schema rather than to the `.02` shape under an `.08`
namespace — which would have "proved version tolerance" while proving nothing. Writing it honestly
exposed a defect the 97 unit tests could not:

- `RltdPties/Dbtr` became a `Party40Choice` in `.04`, so the name lives at `Dbtr/Pty/Nm`. The mapper
  read only `Dbtr/Nm`.
- Financial institutions carry `BICFI` from `.04` onwards, not `BIC`. The mapper had a `BICFI`
  fallback for the *account servicer* but not for `RltdAgts`.

Every counterparty name and BIC therefore came back **null** on a modern file. The balances still
reconciled, so no arithmetic check could catch it — and counterparty name and IBAN are precisely
what the reconciliation work matches suppliers on.

Fixed: `Camt053StatementMapper` now reads both shapes for the counterparty name, the agent BIC and
the SEPA creditor identifier, and this fixture asserts the real values. The lesson generalises —
accepting a version is not the same as reading it correctly, and only a fixture written to that
version's actual schema will tell the difference. A future version that relocates a leaf will parse,
reconcile, and silently return nulls. Adding it here is what catches that.

## The `.expected.json` format

A flat map read by a regex in `GoldenCorpusTest` — `:accounting-banking` has no JSON dependency and must not
gain one for a fixture. Keys starting with `_` are notes and are ignored.

- `outcome` is mandatory: `Success`, `NoStatementFound` or `Failure` (matched as a prefix, so
  `Failure` alone accepts any reason).
- A `Success` expectation **must** state `format`, `transactionCount`, `openingBalance` and
  `closingBalance`, otherwise the test fails: an expectation asserting only `outcome` would pass
  against a statement whose every field was wrong.
- A bare `null` asserts that the parser produced no value — a field it must *not* invent is as much
  a guarantee as one it must read.
- Any other key must be one `GoldenCorpusTest` knows; a typo fails rather than silently dropping an
  assertion.
