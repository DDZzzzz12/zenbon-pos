// :platform-data — the platform area's data tier (spec section 6, `<area>-<tier>`): Exposed tables,
// entities and repositories of the platform features, extracted from :core-data (Stage B2-1b, Task 8).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The platform api tier (DTOs, error types, enums) — `api`: data-tier signatures expose its types.
    api(projects.platformApi)

    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)

    // Exposed ORM — use api() so dependents get Exposed transitively (same lines as :core-data)
    api(libs.exposed.core)
    api(libs.exposed.dao)
    api(libs.exposed.jdbc)
    api(libs.exposed.kotlinDatetime)
    api(libs.exposed.json)

    // PostgreSQL (for ExceptionConverter PSQLException handling)
    implementation(libs.postgresql)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }
}
