// :platform-server — the platform area's server tier (spec section 6, `<area>-<tier>`): routes, services
// and infrastructure of the platform features (auth, terminal, setup, backup, ...), extracted from
// :pos-server (Stage B2-1b, Task 9). :pos-server re-exports it with `api(projects.platformServer)`.
// No io.ktor.plugin here: the root build's `openApiRouteModules` wires the Ktor OpenAPI compiler
// plugin, as for :platform-audit.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `java-library`
}

// Generate BuildConfig for version information. Lives here (moved from :pos-server) because platform
// server files import `org.example.project.BuildConfig`; :pos-server sees it through
// `api(projects.platformServer)`. Same package, object and inputs as before.
val appVersion = project.property("app.version") as String
val appName = project.property("app.name") as String
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildconfig")
    val versionValue = appVersion
    val nameValue = appName
    // Declared as inputs: with only an output dir the task stayed UP-TO-DATE after a version bump, and an
    // incremental build shipped a jar that still reported the previous APP_VERSION.
    inputs.property("appVersion", versionValue)
    inputs.property("appName", nameValue)
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("org/example/project/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package org.example.project

            /**
             * Auto-generated build configuration.
             */
            object BuildConfig {
                const val APP_VERSION: String = "$versionValue"
                const val APP_NAME: String = "$nameValue"
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir(layout.buildDirectory.dir("generated/source/buildconfig"))
    }
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}

// Test hooks widened from `internal` carry @TestOnlyApi (RequiresOptIn, ERROR level). Only test
// compilations opt in; main never does. TestOnlyApiGuardTest fails any build-file line that names it
// without naming a test compilation.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") { compilerOptions.optIn.add("org.example.project.utils.TestOnlyApi") }

tasks.compileKotlin {
    dependsOn(generateBuildConfig)
}

// The generated BuildConfig.kt lives in `sourceSets.main`, so every ktlint task reads it too —
// and without this, Gradle fails the build with an implicit-dependency validation error the moment
// a compile task and ktlintCheck run in one invocation (the natural shape of a verification sweep).
// It reads as a lint failure but is not one: the same commands pass when run separately.
// The generated file itself produces no findings — the root .editorconfig disables ktlint under
// `**/build/**` — so this only fixes the task ordering.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    dependsOn(generateBuildConfig)
}

dependencies {
    // Platform tiers below this one: `api`, because platform-server signatures expose their types.
    api(projects.platformData)
    api(projects.platformServerCommon)

    // Audit trail (TransactionAuditLog writers)
    implementation(projects.platformAudit)

    // Libraries: the Task 3 `external-imports` set for platform-server (docs/audit/2026-10-27-platform-move-plan.md).
    // Ktor server (io.ktor.server.application/auth/request/response/routing/plugins, io.ktor.http)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serializationKotlinxJson)

    // Ktor client (update check against api.github.com; io.ktor.client.engine.okhttp)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientOkHttp)

    // Database: Exposed (via :platform-data), HikariCP, PostgreSQL driver (COPY, PGSimpleDataSource), Flyway
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)

    // Koin (org.koin.core, org.koin.java, org.koin.ktor)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    // Security and Authentication (com.auth0.jwt, at.favre.lib bcrypt)
    implementation(libs.jwt)
    implementation(libs.bcrypt)

    // Serialization, DateTime, Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    // Logging (org.slf4j)
    implementation(libs.logback)

    // Testing (same JUnit platform stack as :pos-server)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2)
}

tasks.test {
    useJUnitPlatform {
        // Same as :pos-server: the default suite is H2-only. PostgreSQL-backed tests are opt-in via
        // :pos-server's postgresTest/realdataTest tasks, and must not move here (plan Review Focus).
        excludeTags("postgres", "realdata")
    }
    // Restart test JVM periodically to prevent H2 in-memory database accumulation
    forkEvery = 5
}
