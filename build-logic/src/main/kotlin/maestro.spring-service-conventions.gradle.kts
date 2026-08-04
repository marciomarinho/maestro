import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("maestro.java-conventions")
    id("org.springframework.boot")
}

val libs = the<LibrariesForLibs>()

dependencies {
    "implementation"(libs.spring.boot.starter.actuator)
    "testImplementation"(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "app.jar"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Services read their configuration from the environment in every environment,
    // so running locally exercises the same code path as the container.
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "local"))
}
