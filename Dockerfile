# Stage 1: Build the Quarkus application
FROM eclipse-temurin:21-jdk-noble@sha256:bf62453dde8d7b979d43a25b8bd14f69902a1bb3b19f5b6572ed7f9fd3c8ae57 AS build

RUN echo 'APT::Get::AllowUnauthenticated "true";' > /etc/apt/apt.conf.d/99insecure && \
    echo 'Acquire::AllowInsecureRepositories "true";' >> /etc/apt/apt.conf.d/99insecure

COPY fortigate-ca.crt /usr/local/share/ca-certificates/fortigate-ca.crt
RUN update-ca-certificates && \
    keytool -importcert -noprompt -trustcacerts \
        -alias fortigate-ca \
        -file /usr/local/share/ca-certificates/fortigate-ca.crt \
        -keystore $JAVA_HOME/lib/security/cacerts \
        -storepass changeit

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

# Stage 2: Runtime image with JDK 21 + Git + Maven 3.9.14 + Node.js 22 + .NET SDK 9
FROM eclipse-temurin:21-jdk-noble@sha256:bf62453dde8d7b979d43a25b8bd14f69902a1bb3b19f5b6572ed7f9fd3c8ae57

RUN echo 'APT::Get::AllowUnauthenticated "true";' > /etc/apt/apt.conf.d/99insecure && \
    echo 'Acquire::AllowInsecureRepositories "true";' >> /etc/apt/apt.conf.d/99insecure

COPY fortigate-ca.crt /usr/local/share/ca-certificates/fortigate-ca.crt
RUN update-ca-certificates && \
    keytool -importcert -noprompt -trustcacerts \
        -alias fortigate-ca \
        -file /usr/local/share/ca-certificates/fortigate-ca.crt \
        -keystore $JAVA_HOME/lib/security/cacerts \
        -storepass changeit

ENV MAVEN_VERSION=3.9.14
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt && \
    mv /opt/apache-maven-${MAVEN_VERSION} ${MAVEN_HOME}

RUN apt-get update && \
    # Apply available security updates for packages with known CVEs (binutils, coreutils, tzdata)
    apt-get upgrade -y --no-install-recommends && \
    apt-get install -y --no-install-recommends git curl ca-certificates gnupg && \
    # Node.js 22.x (for ESLint + Mermaid CLI) — 22.x is the current LTS
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    # Google Chrome stable — avoids the Ubuntu 24.04 chromium snap stub which
    # pulls in snapd (compiled with Go 1.22.2, carrying 3C/11H Go stdlib CVEs).
    curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg && \
    echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        google-chrome-stable \
        fonts-liberation fonts-noto-color-emoji \
        xdg-utils && \
    # Mermaid CLI for local diagram rendering
    npm install -g @mermaid-js/mermaid-cli && \
    # pnpm and yarn — required for JS/TS coverage in projects that use these package managers
    npm install -g pnpm yarn && \
    # .NET SDK 9.0 (for dotnet format + code coverage)
    curl -fsSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --channel 9.0 --install-dir /usr/share/dotnet && \
    ln -s /usr/share/dotnet/dotnet /usr/bin/dotnet && \
    rm /tmp/dotnet-install.sh && \
    # Cleanup
    apt-get purge -y gnupg && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*


# Install dotnet global tools to a shared system path accessible by all users
ENV DOTNET_TOOLS=/usr/share/dotnet-tools
RUN mkdir -p $DOTNET_TOOLS && \
    DOTNET_ROOT=/usr/share/dotnet dotnet tool install --tool-path $DOTNET_TOOLS dotnet-reportgenerator-globaltool
ENV PATH="${DOTNET_TOOLS}:${PATH}"

# Upgrade plexus-utils bundled with Maven 3.9.x from 3.6.0 to 4.0.3 (CVE-2025-67030)
RUN PLEXUS_VERSION=4.0.3 && \
    curl -fsSL "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/${PLEXUS_VERSION}/plexus-utils-${PLEXUS_VERSION}.jar" \
        -o /opt/maven/lib/plexus-utils-${PLEXUS_VERSION}.jar && \
    rm -f /opt/maven/lib/plexus-utils-3.6.0.jar

RUN useradd -m -u 1001 -s /bin/bash appuser && \
    mkdir -p /home/appuser/.m2 /opt/maven-settings && \
    chown -R appuser:appuser /home/appuser/.m2 && \
    printf '# OS\n.DS_Store\n.DS_Store?\n._*\n.Spotlight-V100\n.Trashes\nehthumbs.db\nThumbs.db\ndesktop.ini\n\n# Editor\n.idea/\n.vscode/\n*.iml\n*.swp\n*.swo\n*~\n.project\n.classpath\n.settings/\n\n# Coverage\ncobertura-coverage.xml\n*.lcov\ncoverage/\n\n# Logs\n*.log\n\n# Secrets\n.env\n.env.local\n' > /home/appuser/.gitignore_global && \
    printf '[core]\n\texcludesfile = /home/appuser/.gitignore_global\n' > /home/appuser/.gitconfig && \
    chown appuser:appuser /home/appuser/.gitignore_global /home/appuser/.gitconfig

# Tell Puppeteer to use the system-installed Google Chrome
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/google-chrome-stable

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1
ENV DOTNET_NOLOGO=1
ENV BUILD_MAVEN_HOME=/opt/maven

# Store settings.xml outside of .m2 so it is always available even when a
# named volume is mounted over /home/appuser/.m2.  The entrypoint script
# copies it into place on every container start, ensuring image updates are
# picked up without recreating the volume.
COPY settings.xml /opt/maven-settings/settings.xml

WORKDIR /app

COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar .
COPY --from=build /build/target/quarkus-app/app/ app/
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && \
    chown -R appuser:appuser /app /opt/maven-settings

ENV JAVA_OPTS="-Djava.util.logging.manager=org.jboss.logmanager.LogManager"

USER appuser

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
