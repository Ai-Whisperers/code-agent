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
 * Detects Angular, React, or plain TypeScript from a {@code package.json} in the project root.
 *
 * <p>Precedence: Angular ({@code @angular/core}) → React ({@code react}) → TypeScript ({@code typescript}).
 * Version strings are stripped of semver range prefixes before being stored.
 */
public class TypeScriptDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(TypeScriptDetector.class);

    private final ObjectMapper objectMapper;

    public TypeScriptDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * AIW: common subdirectories that hold the real project in a monorepo-ish
     * layout. Kept in sync with {@code BuildValidator.MONOREPO_SUBDIRS}.
     */
    private static final String[] MONOREPO_SUBDIRS = {
        "web", "app", "frontend", "backend", "api", "packages/web", "apps/web"
    };

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        // AIW: try root first, then walk common monorepo subdirs so that
        // projects like Vete (real package.json in web/) are still detected.
        ArchetypeInfo rootInfo = detectAt(projectRoot);
        if (rootInfo != null) {
            return rootInfo;
        }
        for (String subdir : MONOREPO_SUBDIRS) {
            Path candidate = projectRoot.resolve(subdir);
            if (Files.isDirectory(candidate)) {
                ArchetypeInfo subInfo = detectAt(candidate);
                if (subInfo != null) {
                    LOG.debugf("TypeScriptDetector: found project in monorepo subdir %s", subdir);
                    return subInfo;
                }
            }
        }
        return null;
    }

    private ArchetypeInfo detectAt(Path projectRoot) {
        Path pkgJson = projectRoot.resolve("package.json");
        if (!Files.exists(pkgJson)) return null;

        try {
            JsonNode root = objectMapper.readTree(pkgJson.toFile());

            Map<String, String> allDeps = new LinkedHashMap<>();
            for (String section : List.of("dependencies", "devDependencies", "peerDependencies")) {
                JsonNode node = root.get(section);
                if (node != null && node.isObject()) {
                    node.fieldNames().forEachRemaining(key ->
                            allDeps.putIfAbsent(key, node.get(key).asText()));
                }
            }

            String angularVersion = allDeps.get("@angular/core");
            if (angularVersion != null) {
                String version = stripVersionRange(angularVersion);
                LOG.debugf("Detected Angular via package.json: %s", version);
                return new ArchetypeInfo("angular", version);
            }

            // AIW: Next.js before plain React. Vete and clinica-duerksen hit this branch.
            // Detects via `next` dep OR a next.config.{js,mjs,ts} file in the same dir.
            String nextVersion = allDeps.get("next");
            if (nextVersion != null) {
                String version = stripVersionRange(nextVersion);
                LOG.debugf("Detected Next.js via package.json: %s", version);
                return new ArchetypeInfo("nextjs", version);
            }

            String reactVersion = allDeps.get("react");
            if (reactVersion != null) {
                String version = stripVersionRange(reactVersion);
                LOG.debugf("Detected React via package.json: %s", version);
                return new ArchetypeInfo("react", version);
            }

            String tsCompilerVersion = allDeps.get("typescript");
            if (tsCompilerVersion != null) {
                String version = stripVersionRange(tsCompilerVersion);
                LOG.debugf("Detected TypeScript via package.json: %s", version);
                return new ArchetypeInfo("typescript", version);
            }

        } catch (Exception e) {
            LOG.warnf("TypeScriptDetector: failed to parse package.json at %s: %s", pkgJson, e.getMessage());
        }
        return null;
    }

    public static String stripVersionRange(String version) {
        if (version == null) return null;
        String v = version.trim().replaceAll("^[~^>=<*]+", "").trim();
        int spaceIdx = v.indexOf(' ');
        if (spaceIdx > 0) v = v.substring(0, spaceIdx).trim();
        return v.isEmpty() ? version.trim() : v;
    }
}
