// :backoffice-data — the backoffice area's data tier (spec section 6, `<area>-<tier>`): Exposed tables, entities and
// repositories of the backoffice features, extracted from the former :core-data (deleted in Stage B2-5c).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The backoffice api tier and the accounting, fiscal, ecr and platform data tiers — `api`: data-tier signatures expose their types.
    // No dependency on :shared, :core-data or any ecr/fiscal/accounting server module.
    api(projects.backofficeApi)
    api(projects.accountingData)
    api(projects.fiscalData)
    api(projects.ecrData)
    api(projects.platformData)

    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)

    // Exposed ORM — use api() so dependents get Exposed transitively (same lines as :core-data)
    api(libs.exposed.core)
    api(libs.exposed.dao)
    api(libs.exposed.jdbc)
    api(libs.exposed.kotlinDatetime)
    api(libs.exposed.json)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback)

    // Tests
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.h2)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}

tasks.test {
    useJUnitPlatform()
}
