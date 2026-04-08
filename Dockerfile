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
ENV MAVEN_SHA512=d50af8ab5e6005b46a07f0ce9d3719e67cfdf898da988a84871304cd59fb1af0fef2f99dea709e6e66f21f732f905979b5c2dce6b6860406f60a70e84d9cf0b8
RUN set -e && \
    apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
        -o /tmp/maven.tar.gz && \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - && \
    tar -xz -C /opt -f /tmp/maven.tar.gz && \
    mv /opt/apache-maven-${MAVEN_VERSION} ${MAVEN_HOME} && \
    rm /tmp/maven.tar.gz

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
ENV MAVEN_SHA512=d50af8ab5e6005b46a07f0ce9d3719e67cfdf898da988a84871304cd59fb1af0fef2f99dea709e6e66f21f732f905979b5c2dce6b6860406f60a70e84d9cf0b8
RUN set -e && \
    apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
        -o /tmp/maven.tar.gz && \
    echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - && \
    tar -xz -C /opt -f /tmp/maven.tar.gz && \
    mv /opt/apache-maven-${MAVEN_VERSION} ${MAVEN_HOME} && \
    rm /tmp/maven.tar.gz

ENV NODESOURCE_KEY_SHA512=bf879a71dd828e8ff9cbd0c3706f3bda96b3178e8c4ea2328d9c821411911245aaf643f53251f2d0115928bb41c993f061a02f001876a240095833efb4d0b99c
ENV GOOGLE_KEY_SHA512=3e4df93c53e4dff1ab28443b9619b1963dfa05cb494fe582c75cb58a5396ab21beb985046af853c156d2ad06c615d9ebd5d06d12992b05de05fa9641ba3d11c4
ENV DOTNET_INSTALL_SHA512=971ad8d21a7d17247da2fdfc8867358bde015a7622d09b62324b5d87ddc349c6892727487c700df602e3028eda18216bf40493cb53a989ebf8991dc8cfd78427
RUN set -e && \
    apt-get update && \
    # Apply available security updates for packages with known CVEs (binutils, coreutils, tzdata)
    apt-get upgrade -y --no-install-recommends && \
    apt-get install -y --no-install-recommends git curl ca-certificates gnupg && \
    # Node.js 22.x (for ESLint + Mermaid CLI) — 22.x is the current LTS
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        -o /tmp/nodesource-repo.gpg.key && \
    echo "${NODESOURCE_KEY_SHA512}  /tmp/nodesource-repo.gpg.key" | sha512sum -c - && \
    cat /tmp/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    rm /tmp/nodesource-repo.gpg.key && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    # Google Chrome stable — avoids the Ubuntu 24.04 chromium snap stub which
    # pulls in snapd (compiled with Go 1.22.2, carrying 3C/11H Go stdlib CVEs).
    curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        -o /tmp/linux_signing_key.pub && \
    echo "${GOOGLE_KEY_SHA512}  /tmp/linux_signing_key.pub" | sha512sum -c - && \
    cat /tmp/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg && \
    rm /tmp/linux_signing_key.pub && \
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
    echo "${DOTNET_INSTALL_SHA512}  /tmp/dotnet-install.sh" | sha512sum -c - && \
    chmod +x /tmp/dotnet-install.sh && \
    /tmp/dotnet-install.sh --channel 9.0 --install-dir /usr/share/dotnet && \
    ln -s /usr/share/dotnet/dotnet /usr/bin/dotnet && \
    rm /tmp/dotnet-install.sh && \
    # Adoptium Temurin JDK 8 and 17 — used by CoverageReporter/BuildValidator when a
    # project requires an older Java version than the agent's default JDK 21.
    # JDK 21 is already provided by the base image at $JAVA_HOME.
    # Temurin installs into /usr/lib/jvm/temurin-8 and /usr/lib/jvm/temurin-17.
    ADOPTIUM_KEY_SHA512=f7384c63913a38591a7d1c84937d3f58023f97d092de4dd3fa1436f2370e0d9e00fec84e67920801c26dc52d8462f29afbcd055a25f23b990ee5aca079663784 && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
        -o /tmp/adoptium.gpg.key && \
    echo "${ADOPTIUM_KEY_SHA512}  /tmp/adoptium.gpg.key" | sha512sum -c - && \
    cat /tmp/adoptium.gpg.key | gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg && \
    rm /tmp/adoptium.gpg.key && \
    echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-8-jdk temurin-17-jdk && \
    # Cleanup
    apt-get purge -y gnupg && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*


# Install dotnet global tools to a shared system path accessible by all users
ENV DOTNET_TOOLS=/usr/share/dotnet-tools
RUN mkdir -p $DOTNET_TOOLS && \
    DOTNET_ROOT=/usr/share/dotnet dotnet tool install --tool-path $DOTNET_TOOLS dotnet-reportgenerator-globaltool
ENV PATH="${DOTNET_TOOLS}:${PATH}"

# Upgrade plexus-utils bundled with Maven 3.9.x from 3.6.0 to 4.0.3 (CVE-2025-67030)
ENV PLEXUS_VERSION=4.0.3
ENV PLEXUS_SHA512=ed864c502a54ab2e8e2d4c74479b1cb48c5e44ee56fcad6ba5aec9e20e3e765148299cb8eb8c9a516fb59bf836b12886caca00a9b12eb5cb036271df3437218d
RUN set -e && \
    curl -fsSL "https://repo1.maven.org/maven2/org/codehaus/plexus/plexus-utils/${PLEXUS_VERSION}/plexus-utils-${PLEXUS_VERSION}.jar" \
        -o /opt/maven/lib/plexus-utils-${PLEXUS_VERSION}.jar && \
    echo "${PLEXUS_SHA512}  /opt/maven/lib/plexus-utils-${PLEXUS_VERSION}.jar" | sha512sum -c - && \
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
# Explicit paths for the alternate JDKs installed above.
# JdkResolver uses these as a fast-path before scanning /usr/lib/jvm/.
ENV JAVA_8_HOME=/usr/lib/jvm/temurin-8
ENV JAVA_17_HOME=/usr/lib/jvm/temurin-17
ENV JAVA_21_HOME=/opt/java/openjdk

USER appuser

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
