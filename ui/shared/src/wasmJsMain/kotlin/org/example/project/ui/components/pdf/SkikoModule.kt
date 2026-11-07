@file:JsModule("./skiko.mjs")
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.components.pdf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Skiko's own ES module, which every Compose/Wasm app ships next to its main `.mjs` (ESM caches it, so this is
 * the same instance Compose draws with). `awaitSkiko` resolves to the Emscripten module, whose
 * `wasmExports.memory` is the heap Skia bitmaps live in — the same handle coil3's wasm decoder writes through.
 * Skiko's Kotlin declaration of it is `internal`, hence this re-declaration.
 *
 * LOAD-BEARING for every consumer app (adminApp, composeApp wasm): this is a STATIC import of `./skiko.mjs`
 * resolved next to the consumer's own generated module, so if a Compose/Skiko upgrade renames or moves that
 * file, the consumer's webpack build breaks — not just the fast pixel path. Put it on the Compose-upgrade
 * checklist. (Changes to the module's *shape* are survivable: rgbaPixelsToImageBitmap falls back.)
 */
internal external val awaitSkiko: Promise<JsAny>
