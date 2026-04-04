package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects PHP framework projects from {@code composer.json}.
 *
 * <p>Detection priority: Laravel → Symfony → generic PHP.
 */
public class PhpDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(PhpDetector.class);

    private final ObjectMapper objectMapper;

    public PhpDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        Path composerJson = projectRoot.resolve("composer.json");
        if (!Files.exists(composerJson)) return null;

        try {
            JsonNode root = objectMapper.readTree(composerJson.toFile());

            Map<String, String> allDeps = new LinkedHashMap<>();
            for (String section : List.of("require", "require-dev")) {
                JsonNode node = root.get(section);
                if (node != null && node.isObject()) {
                    node.fieldNames().forEachRemaining(key ->
                            allDeps.putIfAbsent(key, node.get(key).asText()));
                }
            }

            String laravelVersion = allDeps.get("laravel/framework");
            if (laravelVersion != null) {
                String version = TypeScriptDetector.stripVersionRange(laravelVersion);
                LOG.debugf("Detected Laravel via composer.json: %s", version);
                return new ArchetypeInfo("laravel", version);
            }

            String symfonyVersion = allDeps.get("symfony/framework-bundle");
            if (symfonyVersion != null) {
                String version = TypeScriptDetector.stripVersionRange(symfonyVersion);
                LOG.debugf("Detected Symfony via composer.json: %s", version);
                return new ArchetypeInfo("symfony", version);
            }

            JsonNode phpVersion = root.path("require").path("php");
            if (!phpVersion.isMissingNode()) {
                String version = TypeScriptDetector.stripVersionRange(phpVersion.asText());
                LOG.debugf("Detected generic PHP via composer.json: %s", version);
                return new ArchetypeInfo("php", version);
            }

            LOG.debugf("Detected generic PHP project (no framework or php version constraint found)");
            return new ArchetypeInfo("php", "unknown");

        } catch (Exception e) {
            LOG.warnf("PhpDetector: failed to parse composer.json at %s: %s", composerJson, e.getMessage());
        }
        return null;
    }
}
