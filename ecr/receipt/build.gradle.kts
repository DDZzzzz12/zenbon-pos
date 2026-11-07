plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // Shared module (BillDto, PaymentDto, TseReceiptDataDto, TseSigningNoteDto, bill enums, core.utils)
    api(projects.ecrApi)

    // DecimalFormatter (formatDecimal / toBigDecimal), same package, stays in platform
    implementation(projects.platformServerCommon)

    // DsfinvkQrCodeGenerator (zxing only)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    // Tests
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
