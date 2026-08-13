# Build context is the REPO ROOT (not gateway-api/) -- this is a multi-module
# Maven reactor (common-dto, agent-core, agent-runtime, gateway-api), so the
# build stage needs every module's sources to resolve gateway-api's internal
# dependencies. Build from the repo root:
#   docker build -f gateway-api/Dockerfile -t agent-hub-gateway-api .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy every pom.xml first (module source copied separately below) so this
# dependency-resolution layer only invalidates when a pom actually changes,
# not on every source edit.
COPY pom.xml .
COPY common-dto/pom.xml common-dto/pom.xml
COPY agent-core/pom.xml agent-core/pom.xml
COPY agent-runtime/pom.xml agent-runtime/pom.xml
COPY gateway-api/pom.xml gateway-api/pom.xml
RUN mvn -q -pl gateway-api -am dependency:go-offline

COPY common-dto common-dto
COPY agent-core agent-core
COPY agent-runtime/pom.xml agent-runtime/pom.xml
COPY agent-runtime/src agent-runtime/src
COPY gateway-api gateway-api
RUN mvn -q -pl gateway-api -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Runs as a non-root user -- no reason this process needs root inside the container.
RUN addgroup -S app && adduser -S app -G app
# <finalName> in gateway-api/pom.xml drops the version -- the fat jar spring-boot-maven-plugin
# repackages is exactly gateway-api.jar (gateway-api.jar.original is the pre-repackage jar, not this one).
COPY --from=build /build/gateway-api/target/gateway-api.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
