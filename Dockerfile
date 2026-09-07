# Build context MUST be the parent directory of both FinFlow/ and lib-commons/, since they're
# sibling repos and Docker can only COPY files from inside its build context:
#
#   cd "D:/Spring Project"
#   docker build -f FinFlow/Dockerfile -t finflow:latest .
#
# Running `docker build .` from inside FinFlow/ alone will NOT work - lib-commons wouldn't be
# visible to COPY.

# ─── Stage 1: build lib-commons, install it into a local Maven repo ─────────────────────────
FROM maven:3.9.9-eclipse-temurin-22 AS libcommons-builder
WORKDIR /lib-commons
COPY lib-commons/ .
RUN mvn -q install -DskipTests

# ─── Stage 2: build FinFlow, using the .m2 repo populated above ─────────────────────────────
FROM eclipse-temurin:22-jdk AS finflow-builder
WORKDIR /finflow
# FinFlow's build.gradle resolves lib-commons via mavenLocal() - this is what makes that
# resolve inside the container instead of failing with "could not find com.lib-commons:...".
COPY --from=libcommons-builder /root/.m2 /root/.m2
COPY FinFlow/ .
# Tests are skipped here on purpose: FinFlowApplicationTests is a @SpringBootTest needing a real
# Postgres/Redis/Kafka, none of which exist inside this isolated build stage. Run `./gradlew test`
# separately against real local infra, same as always.
RUN ./gradlew :service:bootJar --no-daemon -x test

# ─── Stage 3: runtime - just a JRE and the built jar, nothing else ──────────────────────────
FROM eclipse-temurin:22-jre AS runtime
WORKDIR /app
COPY --from=finflow-builder /finflow/service/build/libs/service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
