# Built from the REPO ROOT, not from this directory:
#   docker build -f services/payments/Dockerfile .
# The reactor pom and the Maven wrapper live at the root and this module
# inherits its versions from that parent, so the build context has to include
# it. docker-compose.yml sets the context accordingly.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Poms and wrapper first: these change far less often than source, so Docker's
# layer cache keeps the dependency download out of the common edit-rebuild loop.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY services/ledger/pom.xml services/ledger/
COPY services/payments/pom.xml services/payments/
# Cache warming only -- if it cannot resolve everything ahead of time, the
# package step below fetches the rest. Failing the build here would trade a
# correct image for a faster one.
RUN ./mvnw -B -pl services/payments -am -DskipTests dependency:go-offline || true

COPY services/ services/
# Tests are skipped on purpose. The suite runs against real Postgres via
# Testcontainers, so running it here would mean Docker inside Docker for no
# benefit: CI already runs it, and an image build is the wrong place to find
# out a test fails.
RUN ./mvnw -B -pl services/payments -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app

# curl is for the compose healthcheck, and stays for the Session 12 chaos
# script, which needs to ask "is it back yet?" from inside the network.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Not root. Nothing here needs it, and a container that a chaos script kills and
# restarts repeatedly is a poor place to be casual about privileges.
RUN useradd --system --create-home --uid 10001 spademoney

COPY --from=build --chown=spademoney:spademoney /build/services/payments/target/payments-*.jar app.jar

USER spademoney
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
