import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("maestro.java-conventions")
}

val libs = the<LibrariesForLibs>()

// Integration tests live in their own source set so they can be run — or skipped —
// independently of the fast unit suite. They exercise real PostgreSQL and Kafka
// through Testcontainers; nothing about the data layer is mocked.
val intTest: SourceSet = sourceSets.create("intTest") {
    compileClasspath += sourceSets["main"].output
    // Includes main's resources as well as its classes, so component scanning and
    // application.yaml resolve exactly as they do at runtime.
    runtimeClasspath += sourceSets["main"].output
}

configurations["intTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["intTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "intTestImplementation"(project(":lib:lib-testing"))
    "intTestImplementation"(libs.bundles.testcontainers)
    "intTestImplementation"(libs.awaitility)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against real infrastructure."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = intTest.output.classesDirs
    // The source set's own runtimeClasspath, not a hand-assembled one: assembling it
    // from configurations alone silently omits main's output, and Spring Boot then
    // cannot find the @SpringBootConfiguration it is meant to be testing.
    classpath = intTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    // Containers make these slow; keep them out of the default build so the
    // inner loop stays fast, and run them explicitly in CI.
    systemProperty("java.util.logging.manager", "java.util.logging.LogManager")
}

tasks.named("check") {
    dependsOn(integrationTest)
}
