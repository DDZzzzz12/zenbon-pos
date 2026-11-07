// :accounting-server — the accounting area's server tier (spec section 6, `<area>-<tier>`): routes, services and
// infrastructure of the accounting features (documents, invoices, bank statements, ...), extracted from
// :pos-server (Stage B2-4). :pos-server re-exports it with `api(projects.accountingServer)`.
// No io.ktor.plugin here: the root build's `openApiRouteModules` wires the Ktor OpenAPI compiler
// plugin, as for :platform-server. Must NOT depend on :ecr-server, :fiscal-server or any backoffice module (R6).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}

// Test hooks widened from `internal` carry @TestOnlyApi (RequiresOptIn, ERROR level). Only test
// compilations opt in; main never does. TestOnlyApiGuardTest fails any build-file line that names it
// without naming a test compilation.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") { compilerOptions.optIn.add("org.example.project.utils.TestOnlyApi") }

// Dependencies: the edges the accounting assume-graph carries (Stage B2-4). No WormAPI test lines: B4 decides if accounting tests need them.
dependencies {
    api(projects.accountingData)
    api(projects.platformServer)
    api(projects.platformServerCommon)
    api(projects.accountingDocument)
    implementation(projects.platformMoney)
    implementation(projects.platformAudit)
    implementation(projects.fiscalExport)
    implementation(projects.ecrReceipt)
    implementation(projects.accountingEinvoice)
    implementation(projects.accountingBanking)

    // Libraries: the `external-imports` set for fiscal-server (starting set from :ecr-server).
    // Ktor server (io.ktor.server.*, io.ktor.http, io.ktor.utils)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serializationKotlinxJson)

    // Koin (org.koin.ktor)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    // Serialization, DateTime, Coroutines (kotlinx.io arrives transitively with Ktor)
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
        // Same as :pos-server: the default suite is H2-only; PostgreSQL-backed tests stay in :pos-server's postgresTest.
        excludeTags("postgres", "realdata")
    }
    // Restart test JVM periodically to prevent H2 in-memory database accumulation
    forkEvery = 5
}

// `Snapshots` re-records only when RECORD_SNAPSHOTS=true (see :pos-server for why it is a provider).
tasks.withType<Test>().configureEach {
    environment("RECORD_SNAPSHOTS", providers.environmentVariable("RECORD_SNAPSHOTS").getOrElse("false"))
}
