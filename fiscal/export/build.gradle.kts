plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "org.example.project"
version = "1.0.0"

dependencies {
    // Fiscal api tier (DTOs: DsfinvkExportDto, KassenbuchEntryType, enums); re-exports platform-api and ecr-api
    implementation(projects.fiscalApi)

    // XML Serialization (xmlutil — kotlinx.serialization XML format for DATEV/DSFinV-K)
    implementation(libs.xmlutil.serialization.jvm)

    // QR Code Generation (DSFinV-K compliance)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    // Testing
    testImplementation(libs.kotlin.testJunit)
}

kotlin {
    jvmToolchain(21)
}
