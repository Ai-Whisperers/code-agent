# Stage 1: Build the Quarkus application
FROM eclipse-temurin:21-jdk AS build

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline -q
COPY src src
RUN mvn -B package -DskipTests -q

# Stage 2: Runtime image with JRE + Git + Maven (needed at runtime for cloning and mvn test)
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends git maven && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar .
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/

ENV JAVA_OPTS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
