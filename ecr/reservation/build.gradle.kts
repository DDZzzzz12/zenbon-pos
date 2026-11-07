plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Server-common utilities (dbQuery, formatters, error handling)
    implementation(projects.platformServerCommon)

    // The ecr data tier (tables, entities, repositories; re-exports :ecr-api, :platform-data and Exposed)
    implementation(projects.ecrData)

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
