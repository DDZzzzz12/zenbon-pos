// :ecr-data — the ecr area's data tier (spec section 6, `<area>-<tier>`): Exposed tables, entities and
// repositories of the ecr features, extracted from :core-data (Stage B2-2, Task B3).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The ecr api tier (DTOs, enums) and the platform data tier — `api`: data-tier signatures expose their types.
    api(projects.ecrApi)
    api(projects.platformData)

    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)

    // Existing ecr leaf modules and the fiscal process-data module the ecr repositories use
    implementation(projects.ecrReceipt)
    implementation(projects.fiscalProcessData)

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
