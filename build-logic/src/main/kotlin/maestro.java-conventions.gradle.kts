import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    java
}

val libs = the<LibrariesForLibs>()

group = "dev.maestro"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-processing",
            "-parameters", // Spring and Jackson bind constructor parameters by name
        )
    )
}

dependencies {
    // Every module resolves versions through the Spring Boot BOM, including plain
    // libraries, so a dependency cannot drift between modules.
    "implementation"(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))

    "testImplementation"(libs.bundles.testing)
    "testRuntimeOnly"(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
