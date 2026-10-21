# The SQL scanner as an image. Its entrypoint runs the scanner, so
# arguments go straight to it:
#
#   docker run --rm -v "$PWD:/repository:ro" -v "$PWD/out:/output" \
#     ghcr.io/graphnous/graphnous-sql-scanner:0.1.0 \
#     --path /repository --target src/main/resources/db/migration \
#     --output /output/scan-result.json
#
# It reads only the SQL files of the repository: it needs no network and
# no database.
#
# The Graphnous server instead overrides the entrypoint with the full
# command from its configuration: java -jar /opt/graphnous/sql-scanner.jar ...

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml ./
COPY sql-scanner-app/pom.xml sql-scanner-app/
RUN mvn -B -q -pl sql-scanner-app dependency:go-offline

COPY sql-scanner-app/src sql-scanner-app/src
RUN mvn -B -q package -DskipTests


FROM eclipse-temurin:25-jre

COPY --from=build /build/sql-scanner-app/target/sql-scanner.jar /opt/graphnous/sql-scanner.jar

# Scans run as an unprivileged user; the repository is mounted read-only
RUN useradd --system --uid 10001 --no-create-home scanner
USER scanner

ENTRYPOINT ["java", "-jar", "/opt/graphnous/sql-scanner.jar"]
