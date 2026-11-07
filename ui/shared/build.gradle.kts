import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        namespace = "org.example.project.sharedui"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(projects.platformApi)
            api(projects.ecrApi)
            api(projects.fiscalApi)
            api(projects.accountingApi)
            api(projects.backofficeApi)

            // Decimal/Money kernel — PosMoneyText formats server money strings without Double
            implementation(projects.platformMoney)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            // WindowSizeClass / currentWindowAdaptiveInfo for responsive shared UI (KpiCardGrid etc.)
            implementation(libs.androidx.material3.adaptive)

            implementation(libs.kotlinx.coroutines.core)

            implementation(libs.coil3.compose)

            // ViewModel-backed overlay surface shared by composeApp and adminApp
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kermit)

            // PosErrorDialogHost timestamps
            implementation(libs.kotlinx.datetime)

            // Session-aware error surfacing (PosErrorWatcher suppresses toasts after logout)
            implementation(libs.koin.compose)

            // koinViewModel() default-parameter resolution (PosTseContent is the DI entry point)
            implementation(libs.koin.composeViewModel)
        }

        androidMain.dependencies {
            // FileProvider — FileSaver's Android actual shares the saved file through it
            implementation(libs.androidx.core.ktx.v1150)
        }

        val desktopMain by getting {
            dependencies {
                // Rasterises PDF pages for the shared PDF viewer's desktop renderer
                implementation(libs.pdfbox)
            }
        }

        val desktopTest by getting {
            dependencies {
                // Skiko natives for the host OS: toComposeImageBitmap() in the PDF renderer tests needs
                // Skia, and compose.ui alone ships only the platform-neutral skiko-awt (no .so/.dylib).
                implementation(compose.desktop.currentOs)
            }
        }

        wasmJsMain.dependencies {
            // kotlinx.browser + org.w3c/org.khronos bindings — FileSaver's wasm actual builds a
            // Blob and clicks an object-URL anchor to hand the file to the browser.
            implementation(libs.kotlinx.browser)

            // PDF viewer engine (Apache-2.0). PdfJsLoader dynamic-imports its legacy build and starts its
            // worker by bare specifier, so both stay out of the consumer's main bundle and no resource file
            // has to propagate. Its optional @napi-rs/canvas dependency is Node-only and never bundled.
            implementation(npm("pdfjs-dist", "6.4.299"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
