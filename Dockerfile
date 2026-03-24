# Stage 1: Build the Quarkus application
FROM eclipse-temurin:21-jdk-noble@sha256:bf62453dde8d7b979d43a25b8bd14f69902a1bb3b19f5b6572ed7f9fd3c8ae57 AS build

RUN echo 'APT::Get::AllowUnauthenticated "true";' > /etc/apt/apt.conf.d/99insecure && \
    echo 'Acquire::AllowInsecureRepositories "true";' >> /etc/apt/apt.conf.d/99insecure

ENV MAVEN_VERSION=3.9.14
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt && \
    mv /opt/apache-maven-${MAVEN_VERSION} ${MAVEN_HOME}

WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline -q
COPY src src
RUN mvn -B package -DskipTests -q

# Stage 2: Runtime image with JDK 21 + Git + Maven 3.9.14 + Node.js + .NET SDK
FROM eclipse-temurin:21-jdk-noble@sha256:bf62453dde8d7b979d43a25b8bd14f69902a1bb3b19f5b6572ed7f9fd3c8ae57

RUN echo 'APT::Get::AllowUnauthenticated "true";' > /etc/apt/apt.conf.d/99insecure && \
    echo 'Acquire::AllowInsecureRepositories "true";' >> /etc/apt/apt.conf.d/99insecure

ENV MAVEN_VERSION=3.9.14
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt && \
    mv /opt/apache-maven-${MAVEN_VERSION} ${MAVEN_HOME}

RUN apt-get update && \
    apt-get install -y --no-install-recommends git curl ca-certificates gnupg && \
    # Node.js 20.x (for ESLint + Mermaid CLI)
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    # Chromium headless dependencies (required by Mermaid CLI / Puppeteer)
    apt-get install -y --no-install-recommends \
        chromium \
        fonts-liberation fonts-noto-color-emoji \
        libgbm1 libasound2t64 libatk1.0-0 libatk-bridge2.0-0 libcups2t64 \
        libdbus-1-3 libdrm2 libgtk-3-0t64 libnspr4 libnss3 libx11-xcb1 \
        libxcomposite1 libxdamage1 libxrandr2 xdg-utils && \
    # Mermaid CLI for local diagram rendering
    npm install -g @mermaid-js/mermaid-cli && \
    # .NET SDK 8.0 (for dotnet format)
    curl -fsSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --channel 8.0 --install-dir /usr/share/dotnet && \
    ln -s /usr/share/dotnet/dotnet /usr/bin/dotnet && \
    rm /tmp/dotnet-install.sh && \
    # Cleanup
    apt-get purge -y gnupg && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1001 -s /bin/bash appuser && \
    mkdir -p /home/appuser/.m2 && \
    chown -R appuser:appuser /home/appuser/.m2

# Tell Puppeteer to use the system-installed Chromium
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_NOLOGO=1

COPY --chown=appuser:appuser settings.xml /home/appuser/.m2/settings.xml

WORKDIR /app

COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar .
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/

RUN chown -R appuser:appuser /app

ENV JAVA_OPTS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager"

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
