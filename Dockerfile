# syntax=docker/dockerfile:1
#
# One multi-stage Dockerfile for all Java services.
#   1. "build" compiles the whole Maven project once (shared by every service image)
#   2. one small runtime stage per service copies only its own jar
# docker-compose.yml picks the stage with `build.target`, e.g. `target: scoring-service`.

# ---------- 1. build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# poms first: this layer (and the download of dependencies) is only redone when a pom changes
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY common/pom.xml common/
COPY tx-simulator/pom.xml tx-simulator/
COPY feature-service/pom.xml feature-service/
COPY scoring-service/pom.xml scoring-service/
COPY decision-api/pom.xml decision-api/
COPY dashboard/pom.xml dashboard/
RUN chmod +x mvnw

COPY common common
COPY tx-simulator tx-simulator
COPY feature-service feature-service
COPY scoring-service scoring-service
COPY decision-api decision-api
COPY dashboard dashboard

# the cache mount keeps ~/.m2 between builds: dependencies are downloaded only once.
# Tests are skipped here: they run with `mvnw verify` before building images.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests \
 && mkdir /jars \
 && for m in tx-simulator feature-service scoring-service decision-api dashboard; do \
      cp "$m"/target/"$m"-*.jar /jars/"$m".jar; \
    done

# ---------- 2. runtime base ----------
# JRE only (no compiler), Ubuntu based: ONNX Runtime needs glibc, so no Alpine
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --create-home --uid 10001 app \
 && mkdir -p /data && chown app /data
WORKDIR /app
# use up to 75% of the container's memory limit for the heap
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ---------- 3. one stage per service ----------
FROM runtime AS tx-simulator
COPY --from=build /jars/tx-simulator.jar /app/app.jar
EXPOSE 8080

FROM runtime AS dashboard
COPY --from=build /jars/dashboard.jar /app/app.jar
EXPOSE 8081

FROM runtime AS decision-api
COPY --from=build /jars/decision-api.jar /app/app.jar
EXPOSE 8082

FROM runtime AS scoring-service
COPY --from=build /jars/scoring-service.jar /app/app.jar
EXPOSE 8083

FROM runtime AS feature-service
COPY --from=build /jars/feature-service.jar /app/app.jar
EXPOSE 8084
