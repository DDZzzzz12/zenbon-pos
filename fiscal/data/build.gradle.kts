// :fiscal-data — the fiscal area's data tier (spec section 6, `<area>-<tier>`): Exposed tables, entities and
// repositories of the fiscal features (TSE, Kassenbuch, Z-Bon, ...), extracted from :core-data (Stage B2-3).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The fiscal api tier, the ecr data tier and the platform data tier — `api`: data-tier signatures expose their types.
    // No dependency on any accounting or backoffice module.
    api(projects.fiscalApi)
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
