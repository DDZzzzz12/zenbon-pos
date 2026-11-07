plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // OrderItemDto, ProductType, Language, printLabel/printedRate/printedAmount (ecr contract tier)
    api(projects.ecrApi)

    // Coroutines (BaseDocumentFormatter switches dispatcher)
    implementation(libs.kotlinx.coroutines.core)

    // Logging (PrintTextTransliterator)
    implementation(libs.logback)

    // Tests (PrintTextTransliteratorTest feeds tseSigningNoteLines output through the emitter)
    testImplementation(projects.ecrReceipt)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
