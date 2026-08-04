# 0001. Monorepo with Gradle convention plugins

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

Maestro is four services, five shared libraries, a web portal and a set of deployment artefacts. They share an event contract, evolve together, and are built by one person on evenings and weekends.

The build must make cross-cutting changes cheap. Adding a field to the event envelope touches a library and three services; if that is four repositories, four pull requests and a version-publishing dance, it will not get done properly — it will get done by copying the type. Build configuration must also not be duplicated eleven times, because eleven copies of a toolchain declaration means the JDK version is upgraded in ten of them.

## Decision

A single repository, a single Gradle build, with shared configuration extracted into **convention plugins** in an included `build-logic` build:

- `maestro.java-conventions` — JDK 25 toolchain, compiler flags, JUnit 5, ArchUnit
- `maestro.library-conventions` — plain `java-library` modules
- `maestro.spring-service-conventions` — Spring Boot plugin, Actuator, OpenTelemetry, container image build
- `maestro.integration-test-conventions` — a separate `integrationTest` source set with Testcontainers

Dependency versions live in a single `gradle/libs.versions.toml` version catalog. No module declares a version inline.

Module dependency directions are enforced by ArchUnit, not by convention: services depend on libraries, services never depend on each other, and `lib-domain` depends on nothing.

## Consequences

**Positive.** A cross-cutting change is one atomic commit with one CI run. There is exactly one place to upgrade the JDK, one place to change test configuration. New modules inherit correct configuration by applying one plugin. A reader can see the entire system at once.

**Negative.** The whole build runs on every change unless configured otherwise; this is mitigated by Gradle's build cache and configuration cache, and at this size a full build is not painful. Convention plugins are a level of indirection that a newcomer must learn.

**Neutral.** Deployment remains per-service; a monorepo does not imply a monolith, and each service produces its own image.

## Alternatives considered

### Multi-repository, one per service

The conventional microservices layout. Rejected because the coordination cost is paid by every change and the benefit — independent release cadence across teams — does not exist for a single author. The failure mode is well known: shared types get copied instead of shared, and the copies diverge.

### A single flat Gradle module

Simplest possible build. Rejected because module boundaries are the mechanism that makes architectural rules enforceable. Without them, "the domain library must not depend on Spring" is a code-review request rather than a build failure.

### Maven

Ubiquitous and familiar. Rejected because convention plugins in Gradle express shared build logic as code rather than as inherited XML, and because Gradle's incremental build and configuration cache matter more on a laptop than in CI.

## Revisit when

More than one person is committing regularly, or a service's release cadence genuinely needs to diverge from the rest.
