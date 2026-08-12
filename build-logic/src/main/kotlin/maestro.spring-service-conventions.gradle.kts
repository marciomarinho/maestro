import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("maestro.java-conventions")
    id("org.springframework.boot")
}

val libs = the<LibrariesForLibs>()

dependencies {
    "implementation"(libs.spring.boot.starter.actuator)
    // Every service is observable the same way: the naming constants and correlation
    // helpers, the Prometheus scrape endpoint, and W3C trace propagation with an OTLP
    // exporter. Added here rather than per service so that a service cannot opt out.
    "implementation"(project(":lib:lib-observability"))
    "implementation"(libs.micrometer.registry.prometheus)
    "implementation"(libs.spring.boot.starter.opentelemetry)
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
