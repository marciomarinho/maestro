# syntax=docker/dockerfile:1

# One Dockerfile for all services. The build stage is identical for each, so Docker
# builds it once and reuses the layer for the other two — the alternative, a Dockerfile
# per service, would triple the build time and give three places for the JDK version to
# drift apart.

FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# Dependency-resolving inputs first, so a source-only change does not re-resolve.
COPY gradlew gradle.properties settings.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
RUN chmod +x gradlew

COPY lib lib
COPY service service

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon \
      :service:payment-api:bootJar \
      :service:router:bootJar \
      :service:ledger:bootJar \
      :service:acquirer-sim:bootJar

FROM eclipse-temurin:25-jre AS runtime
ARG SERVICE
WORKDIR /app

# curl is here for the container healthcheck. The obvious alternative — bash's
# /dev/tcp redirection — silently fails under Docker's CMD-SHELL, which runs /bin/sh.
RUN apt-get update \
 && apt-get install --no-install-recommends -y curl \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged: nothing here needs root, and a container that does not need it should
# not have it.
RUN useradd --system --uid 10001 --create-home maestro
USER maestro

COPY --from=builder --chown=maestro:maestro /workspace/service/${SERVICE}/build/libs/app.jar app.jar

# MaxRAMPercentage rather than a fixed -Xmx, so the heap follows the container limit
# instead of being wrong every time the limit changes.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
