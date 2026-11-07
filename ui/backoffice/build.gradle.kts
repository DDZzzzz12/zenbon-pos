import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Needed to generate serializers for adminApp's own @Serializable classes
    // (type-safe navigation routes). Serialization runtime alone isn't enough.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "adminApp.js"
                devServer =
                    devServer?.copy(
                        port = 8081,
                    )
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.platformApi)
                implementation(projects.ecrApi)
                implementation(projects.fiscalApi)
                implementation(projects.accountingApi)
                implementation(projects.backofficeApi)
                implementation(projects.uiShared)
                implementation(projects.platformMoney)

                // Compose Multiplatform
                implementation(libs.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.components.ui.tooling.preview)
                implementation(libs.material.icons.extended)

                implementation(libs.androidx.material3.adaptive)
                implementation(libs.androidx.material3.adaptive.layout)
                implementation(libs.androidx.material3.adaptive.navigation)
                implementation(libs.androidx.material3.adaptive.navigation.suite)

                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.navigation.compose)

                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.composeViewModel)

                implementation(libs.ktor.clientJs)
                implementation(libs.ktor.clientAuth)
                implementation(libs.ktor.clientContentNegotiation)
                implementation(libs.ktor.clientLogging)
                implementation(libs.ktor.serializationKotlinxJson)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.kermit)

                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor)

                // QR generation for terminal pairing codes (qr-kit has a wasmJs target, verified)
                implementation(libs.qrkit)

                // In-browser document scanner (lazy-loaded via dynamic import(), see OpenCvLoader).
                // Apache-2.0; ~13 MB single file with the wasm inlined, so it must never be a static import.
                implementation(npm("@techstark/opencv-js", "5.0.0-release.1"))
            }
        }

        val wasmJsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // Lets a test fail the engine the way the browser does, to cover the
                // wasm transport-error mapping installed by createPosHttpClient.
                implementation(libs.ktor.clientMock)
            }
        }
    }
}

// A raw horizontalScroll / LazyRow ignores a mouse wheel on wasm (the page scrolls instead of the
// row). PosHorizontalScroller in :ui-shared is the one sanctioned strip. Verified 2026-10-11.
val checkNoRawHorizontalScroll by tasks.registering {
    group = "verification"
    description =
        "Fails if adminApp or shared-ui uses a raw horizontalScroll, LazyRow, LazyHorizontalGrid, LazyHorizontalStaggeredGrid or ScrollableTabRow; use PosHorizontalScroller."
    val root = rootDir
    // shared-ui is consumed by adminApp, so a raw strip there is just as broken.
    val sources =
        files(
            fileTree("src") { include("**/*.kt") },
            fileTree(rootProject.file("ui/shared/src")) {
                include("**/*.kt")
                // The one sanctioned strip wraps the raw widget; the wheel helper sits next to it.
                exclude("**/PosHorizontalScroller.kt", "**/HorizontalWheelScroll.kt")
            },
        )
    inputs.files(sources)
    doLast {
        val raw = Regex("""\b(horizontalScroll|LazyRow|LazyHorizontalGrid|LazyHorizontalStaggeredGrid|\w*ScrollableTabRow)\s*[({]""")
        val offenders =
            sources.files.filter { file ->
                file.readLines().any { line ->
                    val t = line.trim()
                    !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && raw.containsMatchIn(t)
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Raw horizontal scrolling widget in adminApp or shared-ui (mouse wheel does not drive it on wasm). " +
                    "Use PosHorizontalScroller:\n" +
                    offenders.joinToString("\n") { " - ${it.relativeTo(root)}" },
            )
        }
    }
}

tasks.named("check") { dependsOn(checkNoRawHorizontalScroll) }
