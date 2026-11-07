// :platform-api — the platform area's api tier (spec section 6, `<area>-<tier>`): client-safe DTOs,
// enums, error envelope, networking and utilities shared by the server and every client. Same
// targets (android/jvm/wasmJs) and dependency blocks as the former :shared, which it is extracted from (Stage B2-1b, Task 7;
// :shared was deleted in Stage B2-5c).
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
        namespace = "org.example.project.platform.api"
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

            // SharedPreferences KTX extensions used by DeviceInfoProvider.android
            implementation(libs.androidx.core.ktx.v1150)
        }

        jvmMain.dependencies {
            // Ktor Client for JVM/Desktop (api = transitive to consumers)
            api(libs.ktor.clientOkHttp)
        }

        val wasmJsMain by getting {
            dependencies {
                api(libs.ktor.clientJs)
                // kotlinx-browser: provides kotlinx.browser.localStorage/window for the wasmJs
                // DeviceInfoProvider actual. Compose modules pull this transitively, but
                // `platform-api` is Compose-free, so it must be declared here.
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
