package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects SQL-centric projects from {@code *.sql} files scanned up to depth 3.
 * Infers a version from the highest Flyway/Liquibase migration number.
 */
public class SqlDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(SqlDetector.class);

    private static final Pattern MIGRATION_VERSION = Pattern.compile(
            "^[Vv](\\d+(?:[._]\\d+)*)__.*\\.sql$");

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        try (Stream<Path> stream = Files.walk(projectRoot, DockerfileDetector.DOCKERFILE_SCAN_MAX_DEPTH)) {
            List<Path> sqlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .filter(p -> !DockerfileDetector.isInSkippedDir(projectRoot, p))
                    .toList();

            if (sqlFiles.isEmpty()) return null;

            int maxVersion = -1;
            for (Path sql : sqlFiles) {
                Matcher m = MIGRATION_VERSION.matcher(sql.getFileName().toString());
                if (m.matches()) {
                    try {
                        int n = Integer.parseInt(m.group(1).replace("_", "").replace(".", ""));
                        if (n > maxVersion) maxVersion = n;
                    } catch (NumberFormatException ignored) {}
                }
            }

            String version = maxVersion >= 0 ? "V" + maxVersion : "unknown";
            LOG.debugf("Detected SQL project: highest migration version %s", version);
            return new ArchetypeInfo("sql", version);

        } catch (IOException e) {
            LOG.debugf("SqlDetector: cannot walk project: %s", e.getMessage());
            return null;
        }
    }
}
