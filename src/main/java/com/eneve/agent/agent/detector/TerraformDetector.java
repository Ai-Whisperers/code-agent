package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Terraform projects from {@code *.tf} files (scanned up to depth 2) or a
 * {@code .terraform-version} pin file at root.
 */
public class TerraformDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(TerraformDetector.class);

    private static final Pattern REQUIRED_VERSION = Pattern.compile(
            "required_version\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        Path pinFile = projectRoot.resolve(".terraform-version");
        if (Files.isRegularFile(pinFile)) {
            try {
                String v = Files.readString(pinFile, StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    LOG.debugf("Detected Terraform via .terraform-version: %s", v);
                    return new ArchetypeInfo("terraform", v);
                }
            } catch (IOException e) {
                LOG.debugf("TerraformDetector: cannot read .terraform-version: %s", e.getMessage());
            }
        }

        try (Stream<Path> stream = Files.walk(projectRoot, 2)) {
            List<Path> tfFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".tf"))
                    .filter(p -> !DockerfileDetector.isInSkippedDir(projectRoot, p))
                    .sorted()
                    .toList();

            if (tfFiles.isEmpty()) return null;

            for (Path tf : tfFiles) {
                try {
                    String content = Files.readString(tf, StandardCharsets.UTF_8);
                    Matcher m = REQUIRED_VERSION.matcher(content);
                    if (m.find()) {
                        String v = m.group(1).trim();
                        LOG.debugf("Detected Terraform via required_version in %s: %s", tf.getFileName(), v);
                        return new ArchetypeInfo("terraform", v);
                    }
                } catch (IOException e) {
                    LOG.debugf("TerraformDetector: cannot read %s: %s", tf.getFileName(), e.getMessage());
                }
            }

            LOG.debugf("Detected Terraform project (no required_version constraint found)");
            return new ArchetypeInfo("terraform", "unknown");

        } catch (IOException e) {
            LOG.debugf("TerraformDetector: cannot walk project: %s", e.getMessage());
            return null;
        }
    }
}
