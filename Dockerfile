FROM maven:3.9.11-eclipse-temurin-21 AS plugin
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM quay.io/keycloak/keycloak:26.8.2
COPY --from=plugin /build/target/keycloak-snitch-0.1.0.jar /opt/keycloak/providers/
