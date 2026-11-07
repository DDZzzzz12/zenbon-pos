# :platform-money

Scale-aware decimal and money arithmetic for every target in this project:
`android`, `jvm`, `wasmJs`, `iosArm64`, `iosSimulatorArm64`, `iosX64`.

Design: [`docs/superpowers/specs/2026-07-30-money-kmp-module-design.md`](../../docs/superpowers/specs/2026-07-30-money-kmp-module-design.md)

## Status

**The first production call sites landed 2026-07-31**: `Product.price` (commit
`28133b71a`) and the choice-option `priceDelta` (`ChoiceGroup`/`SelectedOption`/
`ChoiceGroupOptionDataManager`, commit `7f3208bef`) carry `Decimal` end-to-end
(DB TEXT ↔ Decimal ↔ UI). The 2026-08-03 slice
([`docs/superpowers/plans/2026-08-03-composeapp-billing-money.md`](../../docs/superpowers/plans/2026-08-03-composeapp-billing-money.md))
extends `Decimal` to the **table-billing/charge path** end-to-end: `BillingMoneyMath`, the
billing ViewModels and the billing UI components, pinned by a 19-test suite (exact arithmetic +
invalid-currency error routing). `adminApp` still has **no** call sites.

The 2026-08-03 slice
([`docs/superpowers/plans/2026-08-03-composeapp-product-extra-money.md`](../../docs/superpowers/plans/2026-08-03-composeapp-product-extra-money.md))
migrates `ProductExtra.price` end-to-end: local storage via `9.sqm` TEXT, wire via
`toPlainString()`, `ProductExtrasDto.validate()` now parses with `Decimal.parse`.

The 2026-08-04 slice retires the **last cents on the table-order screen**:
`TableOrderUiState.discountCents`/`totalCents` (`Cents`) become `discount`/`total` (`Decimal`),
`TableOrderViewModel` stores the summed line amounts exactly, and the one-way `asOrderTotalCents()`
bridge is deleted. The order-summary composables down to `PosOrderSummaryPanel.SummaryRow` now carry
`Decimal` and format through the `:platform-money` path. Pure representational change — order totals were
already whole cents, so no displayed figure moves; the existing `TableOrderViewModelLineTotalTest`
pins the exact totals on the new fields.

The 2026-08-04 bill-panel slice moves the **order-history / bill-detail displays** onto the `:platform-money`
formatter: `PosBillDetailPanel` and `PosBillCard` parse each server money string with
`Decimal.parseOrNull` and render through `Decimal.toFormattedCurrency` (→ `formatMoneyForDisplay`),
replacing the raw `String.toFormattedCurrency` passthrough and every `toCents()` zero-guard. This is
a **visible** change: amounts now render in the shop's `:platform-money` format (e.g. `"16,90 €"` — symbol,
comma decimal, grouping) instead of the old `"16.90 EUR"`, matching every other migrated screen.
`formatMoneyForDisplay`'s own `MoneyDisplayTest` pins the formatting. `PosEditItemPanel`'s remaining
`toCents()` is input-validation, not display, and is left for a later slice.

The 2026-08-04 zBon-voucher slice removes the last `toCents()` **aggregation** defect in
`ZBonDetailPanel`: the vouchers-issued and vouchers-redeemed totals summed each row as
`String → Double → cents (Long)` before formatting, truncating sub-cent precision inside a fiscal
Z-Bon total. They now sum exactly via `mapNotNull { Decimal.parseOrNull(...) }.sum()` and render
through `formatMoneyForDisplay`, matching the Einnahmen/Ausgaben totals already migrated in the same
file. The panel's remaining exact raw-string displays (`"$x €"`) are passthroughs, not `Double`
defects, and a full German-format pass over them is a separate follow-up.

The 2026-08-04 voucher-display slice moves the four voucher **display** components
(`PosVoucherDetailPanel`, `PosVoucherCard`, `VoucherCard`, `VoucherRedemptionCard`) onto `:platform-money`:
amounts render through `String.toDecimalOrZero().toFormattedCurrency()` (visible `"16,90 €"` change,
as with the bill panels), and the redeemed/original **progress-bar ratio** — previously
`String.toDoubleOrNull()` money-in-`Double` — now parses to `Decimal` and only crosses to `Double`
via `toDoubleLossy()` at the final `Float` for `LinearProgressIndicator`. The voucher **input**
dialog (`VoucherDialog`, a `Double` `totalAmount` in state + `toMoneyString`) and the shared
`VoucherDto` `originalValue.toDouble()`/`redeemedAmount.toDouble()` validation are the remaining
voucher work, deferred to their own slice because they change state/input, not just display.

The 2026-08-04 voucher-finish slices complete the voucher path: `VoucherDto`'s two `validate()`
methods parse with `Decimal.parse` (pinned by `VoucherDtoValidationTest`, 8 cases), and
`CounterSaleVoucherState.totalAmount` becomes a `Decimal` (was a `Double` via `toDoubleOrNull` +
`toMoneyString`/`"EUR %.2f".format`), rendered through `Decimal.toFormattedCurrency` in both creation
dialogs — which also removes a commonMain `String.format`.

The 2026-08-04 Kassenbuch **form** slice moves `CreateKassenbuchEntryFormState`'s derived money
(`totalAmount`, `totalIsPositive`, `allLinesValid`) off `Double` — it now sums the user-entered line
strings exactly as `Decimal` — and marks the DTO boundary with an explicit `toDoubleLossy()`.
`settings` carries no money — its `.format` calls are time/file-size only, nothing to migrate there.

The 2026-08-04 **Document/Kassenbuch wire-contract migration** removes money-as-`Double` from the DTO
wire (storage was already `DECIMAL`/`BigDecimal`, so no DB change):

- **Phase 1 (Document request):** `UploadDocumentRequestDto` + `Update{Invoice,BankStatement}MetadataRequestDto`
  main amounts (`netAmount`/`grossAmount`/`taxAmount`/`taxRate`, opening/closing balance) `Double?→String?`.
  The server route parses the multipart part as `String` and the service converts with
  `String.toBigDecimalOrNull()` (was `BigDecimal.valueOf(Double)`); the client sends canonical decimal
  strings via a new `String.toCanonicalMoneyOrNull()`. The LLM extraction path was already
  `BigDecimal`-native and DATEV AP reads the stored `BigDecimal`, so both are unaffected.
- **Phase 2 (Kassenbuch write-path, TSE-gated):** `KassenbuchDto.EntryLineDto.amount` and the internal
  carriers `KassenbuchData.vorzBetrag`/`LineItemData.vorzBetrag` `Double→String`. The server stays pure
  `BigDecimal` end-to-end — the mapper does the sign-flip in `BigDecimal`, and the TSE
  `KassenbuchProcessDataBuilder` parses the exact string via `toBigDecimal(String)`. `:platform-money` `Decimal`
  is used only inside the shared DTO's `validate()`/`calculateTotalAmount()` bodies (no `BigDecimal` in
  `commonMain`). Proven byte-identical by the **399-test DSFinV-K + TSE + Kassenbuch + Z-Bon suite**
  (the golden tests assert exact signed `processData`/DATEV strings).

- **Phase 3 (nested line-item DTOs):** the money/rate fields of `TaxLineItemDto`, `LineItemDto`,
  `PaymentDetailDto`, `DiscountTermDto`, `BankTransactionDto` and the `costAmount`/`exchangeRate`/
  `euTaxRate` trio move `Double→String`. These list DTOs are stored as JSON blobs read with a
  `try { json.decodeFromString(...) } catch → null` under `isLenient = true`, which silently accepts a
  bare JSON number as a String — so **existing stored metadata (numeric JSON) still reads back**; the
  LLM writes raw JSON (lenient reads it) and DATEV AP parses the string via `toBigDecimalOrNull()`.
  Only true counts stay `Double`/`Int` (`LineItemDto.quantity`, `DiscountTermDto.days`). Verified by
  `DatevApLedgerGeneratorTest` + `DocumentRoutesTest` + injection (43 tests). No test constructs these
  DTOs, so nothing else changed.

The Document/Kassenbuch wire is now fully off `Double`.

Behaviour note for the billing slice: sub-cent discount/tip values (e.g. `"0.004"`) are now
**preserved** — the migrated path compares `!= Decimal.ZERO` and sends them exactly, where the old
`toCents()` truncated to 0 and hid them. Intended money-rules behaviour, not a regression.

The dependency is deliberate rather than premature: it makes
`ui/backoffice/src/wasmJsTest/.../MoneyShimPropagationTest.kt` a *standing* guard. That suite caught a
real defect — the wasm engine originally reached decimal.js through a checked-in `.mjs`
**resource**, and Kotlin Gradle does not copy a dependency project's `wasmJsMain/resources` into a
consumer's webpack directory. `:ui-backoffice:compileKotlinWasmJs` passed; only a runtime browser test
in the consumer failed. Without the wiring, that regression could return unnoticed.

The 2026-08-04 zBon-display + edit-panel slice finishes the composeApp surface: `ZBonDetailPanel`'s
remaining raw-string `"$x €"` displays (voucher/redemption/VAT-row/revenue/balances/tips) now render
through `formatMoneyForDisplay(x, "EUR")` like the rest of the panel (visible `"16,90 €"`), and
`PosEditItemPanel`'s price input replaces its `String.toCents()` parseability gate with a lenient
decimal-in-progress regex (so mid-typing `"12."` is not rejected; exact parsing to `Decimal` happens
where the value is used).

The 2026-08-04 dashboard order-card slice removes the last **truncating** `String.toCents()` money
calls from production: `OrderCard` (order total, per-item final price, extras-delta sum) and
`SelectableOrderItemCard` (unit + subtotal) now parse the DTO strings exactly (`toDecimalOrZero()`)
and format through the `Decimal.toFormattedCurrency()` overload (`formatMoneyForDisplay`, visible
`"16,90 €"` instead of the old raw `"16.90 EUR"` passthrough). The sibling cards (`ProductCard`,
`ProductSearchResultItem`, `OrderItemCard`) already took the `Decimal` overload.

The 2026-08-04 **cents-removal** slice then deleted the legacy API outright: the `Cents` (`= Long`)
typealias and every helper — `Double.toCents`/`String.toCents`, `Cents.toAmount`, `Cents.times`,
`Cents.percentage`, `Number.toFormattedString`/`toFormattedStringWithCurrency`, and the cents
`Number.toFormattedCurrency`/`toSignedDeltaCurrency` overloads — are gone from `MoneyFormat.kt` and
`CurrencyFormatUtils.kt`. The sort-position parse in `ProductManagementViewModel` (its only non-money
`toCents()` caller) moved to a plain `toDoubleOrNull()`; the tests that pinned the old cents behaviour
were dropped, and the KDoc/comments that named the deleted symbols were reworded. **No `Cents` /
`toCents` reference survives anywhere in the codebase** (`grep` is clean); `MoneyFormat.kt` now holds
only `toDecimalOrZero`, `toCanonicalMoneyOrNull` and the `discountToPercentage` ratio helper.

With that, the composeApp **display + input** surface is on `:platform-money`, and **`adminApp` is already
migrated** (dashboard/product/extras/cash-balance/Kassenbuch cards all render via `PosMoneyText` /
`formatMoneyForDisplay`; `ProductState` parses prices as `Decimal`) — the earlier note that adminApp
"has no call sites" was stale.

**No shared DTO carries money as `Double` any more.** The 2026-08-04 Kassenbuch **response-detail**
slice was the last one: `KassenbuchEntryResponseDto`/`KassenbuchLineItemDetailDto`/
`KassenbuchEntryLineItemDto.vorzBetrag`, `currentBalance`, `subBalance`, and
`CreateKassenbuchEntryGroupResponseDto.totalGrossAmount` (plus `calculateTotalAmount()`, now returning
an exact `String`) are all `String`. The mapper writes them with `BigDecimal.toDecimalString()`
(no `.toDouble()`), and the client sign math moved off `Double`: `KassenbuchEntryCard`,
`CreateCorrectionDialog` and the `shared-ui` `KassenbuchEntryDetailSections` now flip the document
sign in `Decimal` (`vorzBetrag.toDecimalOrZero().let { if (incoming) it else -it }`) and render via
`formatMoneyForDisplay(..., forceSign = true)`. Verified byte-identical by the **422-test
Kassenbuch + DSFinV-K + Datev suite** (0 skipped/failures). `getCurrentBalance()` stays a plain
server-formatted `String` endpoint (not a detail DTO); `OrderItemDto.discount`, `kostmenge`, `skonto`,
`quantity`, `days` remain `Double`/`Int` because they are **ratios/counts**, not money amounts.

`OrderItemOperationResultDto.totalAmount` is `String` (server builds it via
`sumOf { finalPrice }.toDecimalString()`; no client consumer). `CreateOrderItemRequestDto.unitPrice`
is `String` (the client's `Items.toDto()` sends `Decimal.toPlainString()` instead of `toDoubleLossy()`;
the server parses via `toBigDecimal(String)`; `validate()` parses to `Decimal`).

Done since: `VoucherDto` stats, `FavouriteProductDto.totalRevenue`, `DocumentDto` search bounds
(`minAmount`/`maxAmount`), and `TransactionAuditDto` (`amountImpact` response + `amountMin`/`amountMax`
filter — the audit-log UI in `shared-ui` moved to `formatMoneyForDisplay` + a `String` `AmountFilterField`).

## Why it exists

Money was carried as `Double` in five separate hand-rolled implementations. The worst is
`formatEuro()` in `adminApp`, which truncates the product instead of rounding it:

```
16.9 * 100 = 1689.9999999999998  ->  toLong() = 1689  ->  "16.89 €"   WRONG
1.15 * 100 =  114.99999999999999 ->  toLong() =  114  ->  "1.14 €"    WRONG
```

Sweeping every amount from `0.00` to `20000.00` through it, **131,252 of 2,000,001 (6.6 %) come
out one cent low** — the first is `0.29`, which renders as `"0.28 €"`. Three of the other four
implementations have narrower but real defects of their own; they are enumerated under
[Behaviour changes to expect](#behaviour-changes-to-expect).

`Decimal` has no `Double` constructor and no `toDouble()` — only an explicitly named
`toDoubleLossy()` — so that class of defect cannot be reintroduced silently.

## Correctness

`java.math.BigDecimal` is the oracle. A generator emits the golden vectors from it; the same
conformance suite replays all **6,864** of them on jvm, wasmJs and iosSimulatorArm64 and asserts
byte-identical results. Regenerate the vectors with:

```bash
./gradlew :platform-money:jvmTest --tests "*GoldenVectorGenerator*"
```

Then confirm the committed file is current with `git diff --exit-code`.

`InvariantTest` complements the vectors with the structural guarantees no single vector expresses:
VAT splits always sum back to gross (both directions, both signs), allocations always sum back to
the original, value-equal decimals hash identically on every target, and serialization
round-trips byte-identically.

Values live inside a bounded envelope (`DecimalEnvelope`): at most 30 significant digits, at most
12 fractional digits, and `|v| < 10^18`. Anything outside it throws `DecimalEnvelopeException`
rather than being handed to an engine whose behaviour there is unproven.

## Verify

```bash
./gradlew :platform-money:jvmTest
./gradlew :platform-money:wasmJsBrowserTest
./gradlew :platform-money:iosSimulatorArm64Test
./gradlew :platform-money:compileAndroidMain   # compile only — see Known limitations
```

Three test legs, not four. The wasm task is `wasmJsBrowserTest`, not `wasmJsTest`. The iOS leg
needs macOS. `./gradlew :platform-money:build` does all of the above in one go — it compiles all six
targets and its `check` runs all three legs (`iosX64Test` is skipped on Apple Silicon). Note that
it runs **no** android tests, because the module has no runnable android test task.

`BUILD SUCCESSFUL` on its own is not evidence — a task can be `UP-TO-DATE` or `FROM-CACHE`.
Read the counts out of `platform/money/build/test-results/<leg>/*.xml` and confirm `skipped="0"`: this
project has a JUnit 5 trap where an expression-body `@Test` ending in `assertFailsWith` returns
non-`Unit` and is silently skipped.

## Quick start

```kotlin
val price = Decimal.parse("16.90")                  // never from a Double
val money = Money(price, Currency.EUR)
money.format(MoneyFormat.DE_EUR)                    // "16,90 €"

val split = money.vatSplitFromGross(Percentage.parse("19"))
split.net                                           // 14.20 EUR
split.vat                                           // 2.70 EUR
split.net + split.vat == split.gross                // always true

Money(Decimal.parse("10.00"), Currency.EUR).allocate(3)
// [3.34, 3.33, 3.33] — sums to exactly 10.00
```

## Migration is two jobs, not one

The table below maps **display calls** one-for-one. That is the easy half. The harder and more
valuable half is retiring **cents as a representation** — and doing only the easy half leaves the
real precision loss in place.

### Why cents has to go, not just be re-plumbed

Today a product price reaches the screen like this:

```
server  DECIMAL(19,4)  ->  "16.9000"          exact, 4 decimal places
   ->   ProductDto.price: String              still exact
   ->   price.toDouble()                      ProductDto.kt:84 — LOSSY, and the exact
                                              defect class :platform-money exists to remove
   ->   Double.toCents() = (this*100).roundToLong()
                                              scale 4 collapses to scale 2 — sub-cent
                                              precision the DB holds is now gone
   ->   Product.priceCents: Long              7 domain fields carry this
   ->   17 arithmetic sites operate on Long
   ->   .toFormattedCurrency()                122 display call sites
```

Two independent losses before a single calculation happens: a `Double` hop, and a scale
truncation. With `Decimal` the whole pipeline collapses to **`String -> Decimal -> math -> format`**
— no `Double`, no scale loss, fewer steps. Cents only ever existed because there was no decimal
type; now there is one.

Mapping `.toCents()` straight onto `Decimal.of(cents, 2)` *preserves* the truncation. Prefer
parsing the server's string directly: `Decimal.parse(dto.price)`.

### Measured surface in composeApp

| What | Count | Nature of change |
|---|---|---|
| `.toFormattedCurrency()` | 122 sites | mechanical — swap the call |
| `.toFormattedString()` | 36 sites | mechanical |
| `.toCents()` | 63 sites | most should **disappear**, not be replaced |
| `Cents` typealias (`= Long`) | 19 files | delete the alias once callers are off it |
| Money-typed `Long` fields | 7 (`Product.priceCents`, `ChoiceGroup.priceDeltaCents`, `SelectedOption.priceDeltaCents`, `ProductExtra.price`, …) | **type change** — ripples through state, VMs, mappers |
| Arithmetic on cents | 17 sites | rewrite against `Decimal`/`Money` |
| `String.toDouble()` on money | 8 sites | delete — parse to `Decimal` instead |

The 7 field declarations are the ones to sequence the work around: each is a domain type whose
change ripples through mappers, UI state and ViewModels. The 122 display calls are noisy but
individually trivial.

### Suggested order

1. **Change one domain model end-to-end first** — `Product.priceCents: Long` → `price: Decimal`,
   parsed from `dto.price` with `Decimal.parse`. This exercises mapper, state, VM and display in one
   vertical slice and will surface whatever the map has not anticipated, while touching one feature.
2. **Then the remaining domain fields**, one per change.
3. **Then the display calls in bulk** — by then `Decimal` is already flowing, so they are a
   find-and-replace rather than a redesign.
4. **Delete `Cents`, `toCents`, `toAmount`, `percentage` and the five legacy formatters last**, once
   nothing references them. The compiler will tell you when you are there.

Do **not** introduce a `Decimal -> cents -> Decimal` bridge to ease step 3. It reintroduces the
truncation and tends to become permanent.

## Persistence (SQLDelight)

SQLite has no decimal type — only `NULL`, `INTEGER`, `REAL`, `TEXT`, `BLOB` — so `Decimal` must map
onto one of them.

**Store money as `TEXT` with a `ColumnAdapter`.**

| Option | Verdict |
|---|---|
| `TEXT` + adapter | ✅ Exact, and preserves scale — `"16.9000"` stays scale 4. Identical to the wire format, so the *same string* flows server → DB → memory with no conversion anywhere. |
| `INTEGER` minor units | ❌ What the schema uses today. Exact, but pins scale at 2 and discards the `DECIMAL(19,4)` precision the server holds. |
| `REAL` | ❌ Never — that is a `Double`, the defect this module exists to remove. |

### Current schema

Money is `INTEGER` cents in three places: `local_products.price`,
`local_product_extras.price`, `local_choice_group_options.price_delta`.
`local_r_tables.open_amount` is already `TEXT`, so there is a precedent in the codebase. No money
column is `REAL` (that type appears only for `rotation` and `sort_position`). There are no
`ColumnAdapter`s in the project yet — this would be the first.

### The adapter

```kotlin
object DecimalColumnAdapter : ColumnAdapter<Decimal, String> {
    override fun decode(databaseValue: String): Decimal = Decimal.parse(databaseValue)
    override fun encode(value: Decimal): String = value.toPlainString()
}
```

Wired where the database is constructed:

```kotlin
Database(
    driver = driver,
    local_productsAdapter = Local_products.Adapter(priceAdapter = DecimalColumnAdapter),
)
```

### Sorting: not a problem here, but know why

`TEXT` compares lexicographically, so `"9.00" > "10.00"`. That would break `ORDER BY price`,
`WHERE price > ?` and `SUM(price)`.

It does not bite today: **no query sorts, filters or aggregates a money column** — they are only
ever `INSERT`ed and `SELECT`ed whole (verified across every `.sq` file). If someone later adds such
a query, do the work in Kotlin instead — `List<Money>.sum()` and `Decimal`'s comparison operators
exist for exactly that.

### Migrating INTEGER cents → TEXT

Migrations are at `6.sqm`, so this is `7.sqm`. **Do not route the conversion through `REAL`** —
anything dividing by `100.0` reintroduces a float. Use integer arithmetic, and handle the sign
explicitly:

```sql
CASE WHEN price_delta < 0 THEN '-' ELSE '' END ||
CAST(abs(price_delta) / 100 AS TEXT) || '.' ||
substr('0' || CAST(abs(price_delta) % 100 AS TEXT), -2)
```

The explicit sign is not defensive padding. The obvious shorter form,
`CAST(v/100 AS TEXT) || '.' || substr('0' || CAST(v%100 AS TEXT), -2)`, is **silently wrong for
values between −99 and −1**, because SQLite truncates integer division toward zero (verified with
`sqlite3`):

| cents | naive form | sign-safe form |
|---|---|---|
| `-5` | `"0.-5"` | `-0.05` |
| `-99` | **`"0.99"`** — sign lost | `-0.99` |
| `-250` | `-2.50` | `-2.50` |

`-99` is the dangerous case: it yields a valid-looking string that parses cleanly, so a −0.99
discount becomes +0.99 with nothing to flag it. `price_delta` is signed, so this is reachable.

### `Decimal` or `Money` in persisted models?

`Money` is amount **plus** currency, so persisting it costs **two columns per money field**
(`price TEXT` + `price_currency TEXT`) — or an adapter packing `"16.90 EUR"` into one, which then
has to be re-parsed. The app is single-currency, so a currency column on every row is pure
overhead.

Recommended: **`Decimal` in persisted models, `Money` at the boundaries** where a currency is
genuinely known — bill totals, payments, anything user-facing that needs a symbol.

## Migration map (display calls)

| Existing API | Replacement |
|---|---|
| `Double.toMoneyString()` | `Decimal.format(MoneyFormat.PLAIN)` |
| `Double.toDecimalString2()` | `Decimal.format(MoneyFormat.PLAIN)` |
| `Double.toSignedDecimalString2()` | `Decimal.format(MoneyFormat.PLAIN.copy(forceSign = true))` |
| `Number.toFormattedString()` (cents) | `Decimal.of(cents, 2).format(MoneyFormat.PLAIN)` — **transitional only**, for a display site whose caller still holds cents. Once the domain field is a `Decimal`, format it directly. |
| `Number.toFormattedStringWithCurrency(c)` | `Money.format(MoneyFormat.PLAIN.copy(symbol = c))` |
| `String.toCents()` | `Decimal.parse(text)` — and drop the cents variable entirely; do not re-derive `Long` from it |
| `Double.toCents()` | **delete the call.** Parse the server's string: `Decimal.parse(dto.price)`. Routing through `Double` is the defect. |
| `Cents` typealias | delete once nothing references it — carry `Decimal` (or `Money`) in domain models instead |
| `priceCents: Long` and friends | `price: Decimal`, or `Money` where a currency is genuinely known |
| arithmetic on `Long` cents | `Decimal`/`Money` operators; `allocate()` when splitting so parts sum exactly |
| `String.toDouble()` on a money field | `Decimal.parse(...)` — see `ProductDto.kt:84` |
| `Double.toCents()` | `Decimal.parse(originalString)` — never via `Double` |
| `Cents.toAmount()` | `Decimal.of(cents, 2)` |
| `Cents.percentage(p)` | `Money.percentageOf(Percentage.ofFraction(p))` |
| `Double.toPercentage()` | `Percentage.ofFraction(d).format()` |
| `Double.discountToPercentage()` | `Percentage.ofFraction(Decimal.ONE - multiplier)` |
| `formatEuro()` / `formatSignedEuro()` | `Money.format(MoneyFormat.DE_EUR)` / `.copy(forceSign = true)` |
| `@Composable Number.toFormattedCurrency()` | Stays in `composeApp`; reimplement over `Money.format` with the currency from `SettingManager` |

The `p` and `d` arguments above are `Double` today. They become `Decimal` — parse them from the
original string, never from the `Double`.

Where the existing implementations live:

| File | Provides |
|---|---|
| `platform/api/src/commonMain/kotlin/org/example/project/core/utils/Formatter.kt` | `toMoneyString`, `toPercentage` |
| `platform/api/src/commonMain/kotlin/org/example/project/core/utils/AmountFormatting.kt` | `toDecimalString2`, `toSignedDecimalString2` |
| `ui/shared/src/commonMain/kotlin/org/example/project/core/utils/MoneyFormat.kt` | `toDecimalOrZero`, `toCanonicalMoneyOrNull`, `discountToPercentage` (the `Cents` typealias + `toCents`/`toAmount`/`percentage`/`toFormattedString`/`toFormattedStringWithCurrency` were removed 2026-08-04) |
| `ui/till/src/commonMain/kotlin/org/example/project/core/utils/CurrencyFormatUtils.kt` | `@Composable toFormattedCurrency`, `toSignedDeltaCurrency` |
| `ui/backoffice/src/wasmJsMain/kotlin/org/example/project/admin/core/presentation/format/EuroFormat.kt` | `formatEuro`, `formatSignedEuro` |

### Behaviour changes to expect

**These are corrections, not refactors.** Migrated screens will display different figures. Every
migrated feature's values must be re-checked against the server — do not assume a screen that
looks the same after migration was unaffected, and do not assume a changed figure is a
regression.

Each item below was measured against the current implementations, not inferred:

- **`formatEuro()` / `formatSignedEuro()` truncate; the replacement rounds.** 6.6 % of amounts in
  `0.00`–`20000.00` shift up by one cent (first: `0.29`, today `"0.28 €"`).
- **`formatEuro()` emits a dot separator and no grouping** (`"1234.56 €"`). `MoneyFormat.DE_EUR`
  emits German conventions (`"1.234,56 €"`). To preserve the current appearance during migration
  use `MoneyFormat.PLAIN.copy(symbol = "€", minusSign = "−")` instead.
- **`String.toCents()` truncates a third decimal** (`"16.895"` -> `1689`). `Decimal.parse` keeps
  it; rounding becomes an explicit separate step.
- **`Double.toMoneyString()` is sign-asymmetric.** `roundToLong()` rounds halves toward positive
  infinity, so `-0.125` gives `"-0.12"` and `-2.455` gives `"-2.45"`. `:platform-money` rounds HALF_UP
  (away from zero) on both signs, matching the server, so those become `"-0.13"` and `"-2.46"`.
  `toDecimalString2()` is not affected — it rounds the absolute value first.
- **`Number.toFormattedString()` rounds a fractional-cent input half-to-even.** `kotlin.math.round`
  is `rint`, so a `Double` cents value of `0.5` yields `"0.00"` where HALF_UP yields `"0.01"`;
  every `x.5` case in a 20,000-value sweep differs. Integral cent inputs are unaffected — a sweep
  of 0–5,000,000 cents found zero divergences, so this only bites callers passing fractional cents.
- **`Number.toFormattedString()` saturates above `Int.MAX_VALUE`.** The integer part goes through
  `Double.toInt()`, so `2147483648.00` renders as `"2147483647.00"`. Out of range for a POS
  receipt, but it is a real cliff and `:platform-money` does not have it.

## Known limitations

### Android is compile-verified only

There are **three** test legs — `jvmTest`, `wasmJsBrowserTest`, `iosSimulatorArm64Test`. Android is
covered by `:platform-money:compileAndroidMain` and by sharing the `jvmAndroidMain` engine source set with
jvm; it has **no executed test leg**.

Adding one with `withHostTestBuilder {}` would be *false* assurance, not coverage. Android host
unit tests run on the desktop JVM against `android.jar` stubs, so they would exercise **OpenJDK's**
`java.math.BigDecimal` — byte-for-byte the same code `jvmTest` already runs — and would tell us
nothing about **AOSP's** implementation, which is what actually executes on a device. A green
android host leg would raise the reported leg count from three to four while adding zero new
information, which is worse than the honest gap.

Closing it properly requires **instrumented** tests (`androidTest`, `connectedAndroidTest`) on a
device or emulator, running the same golden corpus. That is a deliberate out-of-scope follow-up:
it needs an emulator in the verification loop, which this module's build does not have.

The residual risk is small but real: AOSP's `BigDecimal` is a separate implementation from
OpenJDK's, and the corpus has never been replayed on it.

### wasm resources do not propagate to a consuming module (RESOLVED)

This was an open risk while the wasmJs engine reached decimal.js through a checked-in
`moneyDecimalShim.mjs` **resource**, imported with a relative
`@file:JsModule("./moneyDecimalShim.mjs")`. Wiring `:platform-money` into `adminApp` confirmed the risk was
real: `:ui-backoffice:compileKotlinWasmJs` passed, but `:ui-backoffice:wasmJsBrowserTest` failed at runtime
with `Can't resolve './moneyDecimalShim.mjs'`. The Kotlin Gradle plugin does **not** copy a
dependency project's `wasmJsMain/resources` into a consumer's webpack/karma directory. npm
dependencies, by contrast, *do* propagate — `decimal.js` appeared in `adminApp`'s generated
`package.json` unaided.

The resource is therefore **gone**. `DecimalJsModule.kt` imports decimal.js by **bare package
specifier** (`@file:JsModule("decimal.js")`), which resolves through `node_modules` in every
consumer, and `DecimalShim.kt` carries the shim body itself in a Kotlin/Wasm `js(...)` block that
the compiler inlines into the generated glue module. There is no longer any file that has to be
copied anywhere.

Two properties of the old shim are load-bearing and are preserved verbatim by the inlined version:

- **Isolation.** The config lives on `Decimal.clone({...})`, never on `Decimal.set(...)`, so no
  other script on the page can change our rounding — and we cannot change theirs.
- **`precision: 40`.** decimal.js defaults to 20 significant digits and `div` reads the
  constructor's precision. The envelope admits results of up to 30 significant digits
  (18 integer + 12 fractional), so 20 would silently truncate them.

**Never reintroduce a relative `./…` module import here.** It compiles everywhere and only fails at
runtime, in the consumer — the single worst failure shape available.
