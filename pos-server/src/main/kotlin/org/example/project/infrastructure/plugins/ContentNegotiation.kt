package org.example.project.infrastructure.plugins

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json(
            Json {
                classDiscriminator = "_type"
                // Compact, deliberately: this is the encoder for every DTO-serialized success
                // response. (Error bodies go through StatusPages' own problemJson, and the SSE routes
                // and file responses bypass ContentNegotiation entirely — all three were already
                // compact.) Pretty printing added an indent + a newline per field for an audience of
                // exactly nobody: no client encodes pretty, and prettyPrint is encode-only, so it
                // never had any bearing on how a response was decoded. Measured on GET /api/products
                // with two seeded rows: 982 -> 621 bytes, 36.8% smaller. Tests that inspect bodies are
                // whitespace-tolerant by construction; do not turn this back on to make a body easier
                // to eyeball.
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
}

fun Application.configureHTTP() {
    install(Compression) {
        // Highest priority. gzip had been commented out as "temporary" and never restored,
        // leaving deflate as the only encoder on offer. This repo's own clients were unaffected —
        // the browser adminApp, desktop and Android all send "gzip, deflate[, br]" and matched
        // deflate. It mattered for gzip-only callers (OkHttp's transparent default,
        // HttpURLConnection, some proxies), which fell through to an uncompressed body; gzip also
        // now wins on priority, and is the more reliably interoperable of the two.
        //
        // minimumSize sits on each encoder for locality, not necessity — it is an extension on
        // ConditionsHolderBuilder, which both CompressionConfig (plugin level) and
        // CompressionEncoderBuilder implement, so either level compiles and works. Declaring it
        // somewhere IS necessary: Ktor falls back to its built-in conditions (a 200-byte minimum
        // plus the image/video/audio/multipart/event-stream exclusions) only when NO condition is
        // configured at all, and the excludeContentType below is one.
        gzip {
            priority = 1.0
            minimumSize(1024)
        }
        deflate {
            priority = 0.9
            minimumSize(1024) // Only compress if response is > 1KB
        }
        // Never compress Server-Sent Events: Ktor's compression does not flush a complete
        // gzip block per event, so the client's gzip decoder reads a truncated member and
        // throws "unexpected end of stream", killing the long-lived notification stream
        // (which carries TSE status pushes) every few seconds.
        excludeContentType(ContentType.Text.EventStream)
        // Already-compressed payloads. Re-compressing them costs CPU proportional to the body size
        // and buys a percent or two at best — worst exactly where the bodies are biggest, i.e. the
        // backup and DSFinV-K/DATEV ZIP exports.
        //
        // Video/Audio/MultiPart are not served today; they are restated because the fallback the
        // comment above describes is already off, so everything Ktor used to exclude for free has
        // to be declared explicitly.
        //
        // Image.Any is deliberately wider than Ktor's own default (JPEG + PNG only): the product
        // and shop-logo uploads also accept webp. It is safe only while no SVG is served —
        // image/svg+xml is XML and compresses well — so revisit this if SVG assets are added.
        //
        // text/csv (the DATEV Buchungsstapel export) is deliberately absent: it compresses well.
        excludeContentType(
            ContentType.Application.Zip,
            ContentType.Application.Pdf,
            ContentType.Image.Any,
            ContentType.Video.Any,
            ContentType.Audio.Any,
            ContentType.MultiPart.Any,
        )
    }
}
