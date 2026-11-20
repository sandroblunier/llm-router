# Multi-arch build (linux/arm64 for DGX Spark / Apple Silicon, linux/amd64):
#   docker buildx build --platform linux/arm64,linux/amd64 -t llm-router:0.1.0 .
# Local single-arch build:
#   docker build -t llm-router:0.1.0 .

FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:26-jre
WORKDIR /app
RUN useradd --system --uid 1001 router && mkdir -p /data && chown router /data
COPY --from=build --chown=router /src/target/quarkus-app/ /app/
USER router
VOLUME /data
EXPOSE 8080
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
