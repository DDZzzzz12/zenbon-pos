@file:JsModule("decimal.js")
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.money

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The decimal.js constructor, imported by **bare package specifier** so it resolves through
 * `node_modules`.
 *
 * This must never go back to a relative import of a checked-in `.mjs` resource. The npm
 * dependency declared by `:platform-money` propagates transitively into every consumer's generated
 * `package.json`, but `wasmJsMain/resources` are **not** copied into a consumer's webpack/karma
 * directory. A `@JsModule("./someShim.mjs")` therefore compiles everywhere and only blows up at
 * runtime, in the consumer, with `Can't resolve './someShim.mjs'`. Bare specifiers do not have
 * that failure mode, so the shim logic lives in `DecimalShim.kt` as inlined `js(...)` glue
 * instead of in a resource file.
 *
 * Named import: decimal.js' ESM build exports `Decimal` alongside its default, and its UMD build
 * sets `Decimal.Decimal = Decimal`, so this resolves whichever build webpack picks.
 */
@JsName("Decimal")
internal external val DecimalCtor: JsAny
