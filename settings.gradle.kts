pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the JDK declared by the toolchain if it is not already installed,
    // so a fresh clone builds without the reader having to install anything.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "maestro"

// Shared libraries. lib-domain depends on nothing; the rest may use Spring.
include("lib:lib-domain")
include("lib:lib-events")
include("lib:lib-observability")
include("lib:lib-outbox")
include("lib:lib-testing")

// Deployable services. Services depend on libraries, never on each other (ADR-0001).
include("service:payment-api")
include("service:router")
include("service:ledger")
include("service:acquirer-sim")
