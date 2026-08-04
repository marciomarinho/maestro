# 0002. Java 25 and Spring Boot 4

- **Status:** Accepted
- **Date:** 2026-08-04

## Context

The platform is I/O-bound almost everywhere: HTTP requests to acquirers, database round trips, Kafka polling. Concurrency model, language version and framework version are therefore load-bearing decisions, and they are also the first thing a JVM interviewer notices.

Two audiences pull in different directions. Employers overwhelmingly run Spring Boot, so using it makes the code immediately assessable. But a project on a version two years stale signals someone who stopped paying attention.

## Decision

**Java 25** (the current long-term-support release) with the toolchain pinned via Gradle so the build is reproducible regardless of what `java -version` reports on the machine — the development machine currently defaults to a GraalVM 26 early-access build, which the toolchain pin makes irrelevant. `.sdkmanrc` pins Temurin 25 for contributors.

**Spring Boot 4** on Spring Framework 7.

**Virtual threads throughout, not reactive.** Request handling and Kafka consumers run blocking code on virtual threads (`spring.threads.virtual.enabled`). Blocking style keeps stack traces readable, debuggers useful, and the code comprehensible to any Java engineer.

Language features are used where they earn their place, not for display: records for the event envelope and value types, sealed interfaces plus pattern matching for acquirer outcomes (so an unhandled case is a compile error rather than a runtime surprise), and virtual threads for concurrency.

**A pre-decided fallback.** If a required dependency has no Spring Boot 4 support at the time it is needed, the fallback is Spring Boot 3.5.x, decided in Phase 1 rather than discovered in Phase 3. The known risk is the Resilience4j Spring Boot starter, which has historically lagged major Boot releases; the mitigation is to use the Resilience4j core modules directly with a hand-written Micrometer binding — which is arguably the better showcase anyway, since it makes the circuit-breaker configuration explicit instead of hidden behind a starter.

## Consequences

**Positive.** Thousands of concurrent in-flight acquirer calls without a thread-pool sizing exercise or a reactive rewrite. Straight-line code that reads the way the business logic reads. Current versions, which signal engagement with the platform.

**Negative.** Java 25 and Boot 4 are recent enough that some third-party libraries lag; the fallback exists for exactly this. Virtual threads have a pinning failure mode inside `synchronized` blocks holding a lock across a blocking call — addressed by preferring `ReentrantLock` in the hot path and by verifying with JDK Flight Recorder's `jdk.VirtualThreadPinned` event during the Phase 4 load tests, with the result published in the load report.

**Neutral.** Virtual threads change how thread-related metrics are interpreted; the dashboards account for it.

## Alternatives considered

### Reactive with Spring WebFlux and Project Reactor

The pre-Loom answer to high-concurrency I/O. Rejected because virtual threads deliver the same benefit without colouring every function, without an operator-chain learning curve, and without destroying stack traces. Choosing reactive in 2026 for a greenfield JVM service needs a specific justification — such as backpressure semantics across a streaming pipeline — that this system does not have.

### Java 21, the previous LTS

Safest possible dependency situation. Rejected because Java 25 is the current LTS, the features used are stable, and the toolchain pin removes the practical risk.

### Quarkus

Faster startup and a strong native-image story, and GraalVM is already installed on the machine. Rejected because Spring Boot is what the hiring market runs, and startup time is irrelevant for long-lived services. Native compilation would trade real engineering time for a benefit this system does not need.

## Revisit when

A required dependency proves incompatible with Boot 4 in a way the fallback does not cover, or load testing shows virtual-thread pinning that cannot be resolved.
