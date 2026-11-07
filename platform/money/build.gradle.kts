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
        namespace = "org.example.project.money"
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

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    sourceSets {
        // jvm and android both have java.math.BigDecimal but get no shared parent
        // from the default hierarchy template, so link one manually.
        val commonMain by getting
        val jvmAndroidMain by creating { dependsOn(commonMain) }
        val jvmMain by getting { dependsOn(jvmAndroidMain) }
        val androidMain by getting { dependsOn(jvmAndroidMain) }

        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.core)
            // Needed only to recognise JsonDecoder and reject JSON numbers for money. The
            // serializers stay format-agnostic: any non-JSON Decoder falls back to decodeString().
            implementation(libs.kotlinx.serialization.json)
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(npm("decimal.js", "10.6.0"))
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
