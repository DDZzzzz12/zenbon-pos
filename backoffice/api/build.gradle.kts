// :backoffice-api — the backoffice area's api tier (spec section 6, `<area>-<tier>`): client-safe DTOs, enums and API clients of the
// backoffice features (employees, statistics, reports, exports, ...), extracted from the former :shared (deleted in Stage B2-5c).
// Same targets (android/jvm/wasmJs) as :platform-api.
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        namespace = "org.example.project.backoffice.api"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
    }

    jvm {
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
            // The platform, ecr, fiscal and accounting api tiers: `api`, because backoffice DTOs expose their types.
            api(projects.platformApi)
            api(projects.ecrApi)
            api(projects.fiscalApi)
            api(projects.accountingApi)

            // Exact decimal kernel (:platform-money), `implementation` scope.
            implementation(projects.platformMoney)

            // Serialization and DateTime
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)

            // Coroutines (api = transitive to consumers)
            api(libs.kotlinx.coroutines.core)

            // Koin DI (api = transitive to consumers)
            api(libs.koin.core)

            // Ktor Client for shared networking (api = transitive to consumers)
            api(libs.ktor.clientCore)
            api(libs.ktor.clientAuth)
            api(libs.ktor.clientContentNegotiation)
            api(libs.ktor.clientSerialization)
            api(libs.ktor.clientLogging)
            api(libs.ktor.clientEncoding)
            api(libs.ktor.serializationKotlinxJson)
        }

        androidMain.dependencies {
            // Ktor Client for Android (api = transitive to consumers)
            api(libs.ktor.clientOkHttp)

            // Koin Android (api = transitive to consumers)
            api(libs.koin.android)

            // SharedPreferences KTX extensions (copied from the ecr-api build; backoffice/api has no android source set of its own)
            implementation(libs.androidx.core.ktx.v1150)
        }

        jvmMain.dependencies {
            // Ktor Client for JVM/Desktop (api = transitive to consumers)
            api(libs.ktor.clientOkHttp)
        }

        val wasmJsMain by getting {
            dependencies {
                api(libs.ktor.clientJs)
                // kotlinx-browser: provides kotlinx.browser.localStorage/window (copied from the ecr-api build;
                // backoffice/api has no wasmJs source set of its own). Compose modules pull this transitively, but
                // `backoffice-api` is Compose-free, so it must be declared here.
                implementation(libs.kotlinx.browser)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.clientMock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
