plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Area api modules (DTOs, error types, enums)
    implementation(projects.platformApi)
    implementation(projects.backofficeApi)

    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)

    // Backoffice data tier (database tables, entity models, repositories — provides Exposed transitively)
    implementation(projects.backofficeData)

    // Ktor (for route definitions)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Logging
    implementation(libs.logback)
}

kotlin {
    jvmToolchain(21)
}
