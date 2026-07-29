# syntax=docker/dockerfile:1

# ---------- Stage 1: build ----------
# Full JDK + Maven. This stage is thrown away; only the jar survives.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM alone first. As long as pom.xml doesn't change, Docker reuses the
# cached dependency layer instead of re-downloading the internet on every build.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

# Now the source. Changing a .java file only invalidates from here down.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# Spring Boot fat jars can be split into layers that change at different rates
# (deps rarely, your classes constantly). Extracting them keeps image pushes small.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---------- Stage 2: runtime ----------
# JRE only — no compiler, no Maven, no source code. Smaller and less to attack.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Never run as root inside a container.
RUN addgroup -S spring && adduser -S spring -G spring

# Copy layers most-stable-first so the volatile one lands last.
COPY --from=build --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/application/ ./

USER spring
EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container's memory limit
# rather than from the host's total RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
