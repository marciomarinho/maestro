plugins {
    `kotlin-dsl`
}

// The convention plugins are compiled by whichever JDK is running Gradle, which need
// not be the project's toolchain. Pinning it here keeps the Kotlin and Java compile
// targets consistent regardless of the launcher.
kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

dependencies {
    implementation(libs.plugin.springBoot)

    // Makes the version catalog's generated accessors visible to the precompiled
    // script plugins in this build, so conventions and modules share one source
    // of truth for versions (ADR-0001).
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
