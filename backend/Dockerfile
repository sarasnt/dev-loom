# ---- build stage: JDK 25 + Maven (no local Java needed) ----
FROM eclipse-temurin:25-jdk AS build
ARG MAVEN_VERSION=3.9.16
ENV MAVEN_HOME=/opt/maven
RUN set -eux; \
    apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tgz; \
    mkdir -p "$MAVEN_HOME"; tar -xzf /tmp/maven.tgz -C "$MAVEN_HOME" --strip-components=1; \
    ln -s "$MAVEN_HOME/bin/mvn" /usr/local/bin/mvn; rm /tmp/maven.tgz
WORKDIR /app
# cache dependencies first
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
# build + run unit tests (pure — no DB/containers needed)
COPY src ./src
RUN mvn -B package

# ---- runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
# curl for the compose healthcheck (the JRE image ships neither curl nor wget).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/devloom-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
