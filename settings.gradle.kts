rootProject.name = "POS"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()

        maven("https://jogamp.org/deployment/maven")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":ui-till")
include(":ui-backoffice")
include(":ui-shared")
include(":ui-till-android")

// Client modules live under ui/ (Gradle project names: :ui-shared, :ui-till, :ui-till-android, :ui-backoffice).
project(":ui-shared").projectDir = file("ui/shared")
project(":ui-till").projectDir = file("ui/till")
project(":ui-till-android").projectDir = file("ui/till-android")
project(":ui-backoffice").projectDir = file("ui/backoffice")
include(":pos-server")
include(":fiscal-export")
include(":fiscal-process-data")
include(":ecr-receipt")
include(":pos-tse")
include(":platform-money")
include(":platform-server-common")
include(":ecr-customer")
include(":ecr-reservation")
include(":platform-audit")
include(":platform-api")
include(":platform-data")
include(":platform-server")
include(":ecr-api")
include(":ecr-data")
include(":ecr-server")
include(":fiscal-api")
include(":fiscal-data")
include(":fiscal-server")
include(":accounting-api")
include(":accounting-data")
include(":accounting-server")
include(":backoffice-api")
include(":backoffice-data")
include(":backoffice-server")
include(":backoffice-timetracking")
include(":ecr-printer-core")
include(":ecr-printer")
include(":ecr-voucher")
include(":platform-secure-xml")
include(":accounting-einvoice")
include(":accounting-banking")
include(":accounting-document")

// Platform modules live under platform/ (Gradle project names: :platform-money, :platform-secure-xml,
// :platform-server-common, :platform-audit, and the <area>-<tier> modules :platform-api, :platform-data, :platform-server).
project(":platform-money").projectDir = file("platform/money")
project(":platform-secure-xml").projectDir = file("platform/secure-xml")
project(":platform-server-common").projectDir = file("platform/server-common")
project(":platform-audit").projectDir = file("platform/audit")
project(":platform-api").projectDir = file("platform/api")
project(":platform-data").projectDir = file("platform/data")
project(":platform-server").projectDir = file("platform/server")

// Ecr area modules (Gradle project names: <area>-<tier>, plus the leaf modules :ecr-customer, ...).
project(":ecr-api").projectDir = file("ecr/api")
project(":ecr-data").projectDir = file("ecr/data")
project(":ecr-server").projectDir = file("ecr/server")

// Fiscal area tier modules (Stage B2-3; :fiscal-export and :fiscal-process-data are leaf modules below).
project(":fiscal-api").projectDir = file("fiscal/api")
project(":fiscal-data").projectDir = file("fiscal/data")
project(":fiscal-server").projectDir = file("fiscal/server")

// Accounting area tier modules (Stage B2-4; :accounting-document, :accounting-einvoice and :accounting-banking are leaf modules below).
project(":accounting-api").projectDir = file("accounting/api")
project(":accounting-data").projectDir = file("accounting/data")
project(":accounting-server").projectDir = file("accounting/server")

// Backoffice area tier modules (Stage B2-5; :backoffice-timetracking is a leaf module below).
project(":backoffice-api").projectDir = file("backoffice/api")
project(":backoffice-data").projectDir = file("backoffice/data")
project(":backoffice-server").projectDir = file("backoffice/server")

// Accounting leaf modules live under accounting/ (Gradle project names: :accounting-einvoice, :accounting-banking, :accounting-document).
project(":accounting-einvoice").projectDir = file("accounting/einvoice")
project(":accounting-banking").projectDir = file("accounting/banking")
project(":accounting-document").projectDir = file("accounting/document")

// Remaining leaf modules live under fiscal/, ecr/ and backoffice/ (:pos-tse stays at the repo root).
project(":fiscal-export").projectDir = file("fiscal/export")
project(":fiscal-process-data").projectDir = file("fiscal/process-data")
project(":ecr-receipt").projectDir = file("ecr/receipt")
project(":ecr-printer").projectDir = file("ecr/printer")
project(":ecr-printer-core").projectDir = file("ecr/printer-core")
project(":ecr-reservation").projectDir = file("ecr/reservation")
project(":ecr-voucher").projectDir = file("ecr/voucher")
project(":ecr-customer").projectDir = file("ecr/customer")
project(":backoffice-timetracking").projectDir = file("backoffice/timetracking")
