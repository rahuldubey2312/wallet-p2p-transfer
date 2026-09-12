# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Optional JVM options for the build, e.g. a corporate proxy. Empty by default
# so the image builds unchanged on a hosted builder.
ARG GRADLE_OPTS=""
ENV GRADLE_OPTS=${GRADLE_OPTS}

# Wrapper and build scripts first: dependency resolution is then cached and
# only re-runs when the build definition itself changes, not on every edit.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew \
 && ./gradlew --no-daemon dependencies --configuration runtimeClasspath

COPY src ./src
RUN ./gradlew --no-daemon bootJar

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Unprivileged, no login shell. BusyBox already provides the wget used by the
# health check, so the runtime image needs no extra packages.
RUN addgroup -S -g 10001 wallet \
 && adduser -S -u 10001 -G wallet -s /sbin/nologin wallet

WORKDIR /app
COPY --from=build --chown=wallet:wallet /build/build/libs/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

# A percentage rather than a fixed heap, so the JVM adapts to whatever memory
# the free tier actually grants the container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider "http://127.0.0.1:${PORT:-8080}/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
