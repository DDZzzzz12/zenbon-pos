# adminApp CJK font

`NotoSansSC-GB2312-v2.otf` is a **subset** of Noto Sans SC (Simplified Chinese), served at `/font/…`
and fetched by the shared `CjkFontLoader` (shared-ui, used by adminApp and the browser till) — Compose/wasm draws through Skia, which cannot use a
browser-registered web font, so the panel hands the raw bytes to Skia itself.

## Why a subset

The full Noto Sans SC is ~8 MB / 30,890 glyphs (all of GB18030, most of it rare or historic). This
subset keeps **GB2312** (the 6,763 everyday characters) plus every Chinese character the app's own UI
strings use, plus Latin, punctuation and full-width forms — 8,052 glyphs, **~1.6 MB**. That is a 5×
smaller download and a faster Skia parse, so switching the panel to Chinese no longer flashes tofu
boxes while a large file loads. `CjkFontLoader` also pre-warms it when the language menu opens.

## Trade-off / how to widen it

GB2312 covers essentially all everyday Chinese, but an **uncommon character** — for example a rare
hanzi in a customer or product name (生僻字) — is not in GB2312 and will render as a tofu box. If that
ever shows up in the field, widen the subset. The full Noto Sans SC lives in Google's Noto release
(OFL, same as the retained `LICENSE.txt`). Regenerate with fonttools:

```bash
python3 -m venv fontvenv && ./fontvenv/bin/pip install fonttools
# 1. common characters — GB2312, or a wider set (e.g. GBK ~21k, or all CJK U+4E00-9FFF ~21k):
#    all-modern-CJK is name-safe (5 MB): add  U+4E00-9FFF  to the --unicodes below and drop --text-file.
# 2. the app's own UI characters must always be included, or the UI itself tofus.
./fontvenv/bin/pyftsubset NotoSansSC-Regular-FULL.otf \
  --text-file=gb2312-plus-ui-chars.txt \
  --unicodes="U+0020-007E,U+00A0-00FF,U+2000-206F,U+20A0-20CF,U+3000-303F,U+FF00-FFEF" \
  --no-hinting --output-file=NotoSansSC-GB2312.otf
```

## Currency symbols — do not drop U+20A0-20CF

`AdminTheme` applies this font to the **whole** type scale, and Compose does not fall back by glyph
coverage, so a codepoint missing here renders as *nothing at all* — not a tofu box. The first subset
omitted the Currency Symbols block, and because `€` is U+20AC (just past the punctuation range that
was included) every euro amount in the back office silently lost its symbol: "3.622,03" where the
till showed "3.622,03 €". `$`, `£` and `¥` were unaffected — they live in the Latin ranges — which is
why it read as a data bug rather than a font one for a long time.

The `--unicodes` list below now covers U+20A0-20CF. Keep it there, and after regenerating check:

```bash
./fontvenv/bin/python -c "from fontTools.ttLib import TTFont; f=TTFont('NotoSansSC-GB2312-v2.otf'); \
print(0x20AC in {c for t in f['cmap'].tables for c in t.cmap})"
# must print True
```

## Do not reach for a glyph outside the subset

The same trap caught the minus sign. `formatMoneyForDisplay` used U+2212 MINUS SIGN — correct
typography, digit-width, and absent from this subset — so every negative amount in the back office
rendered without it: a −42,30 € debit drawn as "42,30 €", which on a bank statement is not a cosmetic
loss but the wrong number. It is now an ASCII hyphen, guarded by `MoneyDisplayTest`.

The rule that follows: text this font renders must stay inside the `--unicodes` ranges above. Widening
the subset for one glyph fixes that glyph and leaves the next one to be found the way these two were —
by someone noticing a figure that looks wrong. Prefer an ASCII character wherever one exists.

The translation strings hit it a third time (QA admin19): the Berichte reconciliation line wrote its
subtraction with U+2212 and rendered with a gap where the operator stood. adminApp's
`FontSubsetCoverageTest` now checks **every registered translation** against this file's exact cmap
(non-CJK codepoints listed in the test; CJK ideographs allowed wholesale), so a new string with an
uncovered glyph fails the build instead of being found in the field. Regenerating the subset means
updating that list from the new cmap.

Ship any regenerated font under a **new filename** and update `CjkFontLoader.FONT_PATH`: the file is
served with a one-year `Cache-Control`, so reusing the name would leave cached clients on the old
bytes.
