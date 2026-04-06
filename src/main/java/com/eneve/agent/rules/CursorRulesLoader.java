package com.eneve.agent.rules;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CursorRulesLoader {

    private static final Logger LOG = Logger.getLogger(CursorRulesLoader.class);

    @Inject SettingsService settings;

    /**
     * Load rules from an external shared rules repo by name.
     */
    public List<String> loadFromRulesRepo(String rulesRepoUrl, List<String> ruleNames) {
        if (rulesRepoUrl == null || rulesRepoUrl.isBlank()) {
            return Collections.emptyList();
        }

        try {
            Path repoDir = ensureRulesRepoCloned(rulesRepoUrl);
            return loadNamedRules(repoDir, ruleNames);
        } catch (Exception e) {
            LOG.warnf("Failed to load rules from repo %s: %s", rulesRepoUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Scan the target (cloned) repo for Cursor rules.
     */
    public List<String> loadFromTargetRepo(Path workspaceRoot) {
        if (!Boolean.parseBoolean(settings.get("rules.auto-read-target-repo", "true"))) {
            return Collections.emptyList();
        }

        List<String> bodies = new ArrayList<>();

        Path cursorRulesDir = workspaceRoot.resolve(".cursor/rules");
        if (Files.isDirectory(cursorRulesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(cursorRulesDir, "*.mdc")) {
                for (Path file : stream) {
                    MdcRule rule = MdcParser.parse(file.getFileName().toString(), Files.readString(file));
                    if (rule.alwaysApply() && !rule.body().isBlank()) {
                        bodies.add("# Rule: " + rule.fileName() + "\n" + rule.body());
                    }
                }
            } catch (IOException e) {
                LOG.warnf("Failed to read .cursor/rules/: %s", e.getMessage());
            }
        }

        Path cursorrules = workspaceRoot.resolve(".cursorrules");
        if (Files.isRegularFile(cursorrules)) {
            try {
                String content = Files.readString(cursorrules).strip();
                if (!content.isBlank()) {
                    bodies.add("# .cursorrules\n" + content);
                }
            } catch (IOException e) {
                LOG.warnf("Failed to read .cursorrules: %s", e.getMessage());
            }
        }

        Path agentsMd = workspaceRoot.resolve("AGENTS.md");
        if (Files.isRegularFile(agentsMd)) {
            try {
                String content = Files.readString(agentsMd).strip();
                if (!content.isBlank()) {
                    bodies.add("# AGENTS.md\n" + content);
                }
            } catch (IOException e) {
                LOG.warnf("Failed to read AGENTS.md: %s", e.getMessage());
            }
        }

        Path agentMd = workspaceRoot.resolve("AGENT.md");
        if (Files.isRegularFile(agentMd)) {
            try {
                String content = Files.readString(agentMd).strip();
                if (!content.isBlank()) {
                    bodies.add("# AGENT.md\n" + content);
                }
            } catch (IOException e) {
                LOG.warnf("Failed to read AGENT.md: %s", e.getMessage());
            }
        }

        return bodies;
    }

    /**
     * Assemble the full system prompt from all layers.
     */
    public String buildSystemPrompt(List<String> sharedRules, List<String> repoRules,
                                    String extraRules, String guardrails, String taskPrompt) {
        StringBuilder sb = new StringBuilder();

        if (!sharedRules.isEmpty()) {
            sb.append("=== PROJECT CODING STANDARDS (shared rules) ===\n\n");
            sharedRules.forEach(r -> sb.append(r).append("\n\n"));
        }

        if (!repoRules.isEmpty()) {
            sb.append("=== REPOSITORY CONVENTIONS ===\n\n");
            repoRules.forEach(r -> sb.append(r).append("\n\n"));
        }

        if (extraRules != null && !extraRules.isBlank()) {
            sb.append("=== ADDITIONAL INSTRUCTIONS ===\n\n");
            sb.append(extraRules).append("\n\n");
        }

        sb.append("=== MANDATORY GUARDRAILS (non-negotiable) ===\n\n");
        sb.append(guardrails).append("\n\n");

        sb.append("=== YOUR TASK ===\n\n");
        sb.append(taskPrompt).append("\n");

        return sb.toString();
    }

    private Path ensureRulesRepoCloned(String repoUrl) throws IOException, InterruptedException {
        Path cachePath = Path.of(settings.get("rules.repo.cache.dir", "/tmp/cursor-rules-cache"));
        String dirName = repoUrl.replaceAll("[^a-zA-Z0-9]", "_");
        Path repoDir = cachePath.resolve(dirName);

        if (Files.isDirectory(repoDir.resolve(".git"))) {
            pullLatest(repoDir);
        } else {
            Files.createDirectories(repoDir);
            cloneRepo(repoUrl, repoDir);
        }

        return repoDir;
    }

    private List<String> loadNamedRules(Path repoDir, List<String> ruleNames) {
        Path rulesDir = repoDir.resolve(".cursor/rules");
        if (!Files.isDirectory(rulesDir)) {
            rulesDir = repoDir;
        }

        List<String> bodies = new ArrayList<>();

        if (ruleNames == null || ruleNames.isEmpty()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDir, "*.mdc")) {
                for (Path file : stream) {
                    MdcRule rule = MdcParser.parse(file.getFileName().toString(), Files.readString(file));
                    if (rule.alwaysApply() && !rule.body().isBlank()) {
                        bodies.add("# Rule: " + rule.fileName() + "\n" + rule.body());
                    }
                }
            } catch (IOException e) {
                LOG.warnf("Failed to scan rules directory: %s", e.getMessage());
            }
        } else {
            for (String name : ruleNames) {
                Path mdcFile = rulesDir.resolve(name + ".mdc");
                if (!Files.isRegularFile(mdcFile)) {
                    mdcFile = rulesDir.resolve(name + ".md");
                }
                if (Files.isRegularFile(mdcFile)) {
                    try {
                        MdcRule rule = MdcParser.parse(mdcFile.getFileName().toString(),
                                Files.readString(mdcFile));
                        if (!rule.body().isBlank()) {
                            bodies.add("# Rule: " + name + "\n" + rule.body());
                        }
                    } catch (IOException e) {
                        LOG.warnf("Failed to read rule '%s': %s", name, e.getMessage());
                    }
                } else {
                    LOG.warnf("Rule file not found for name '%s'", name);
                }
            }
        }

        return bodies;
    }

    private void cloneRepo(String url, Path target) throws IOException, InterruptedException {
        String authUrl = injectCredentials(url);
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", authUrl,
                target.toString())
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.waitFor(5, TimeUnit.MINUTES);
        if (proc.exitValue() != 0) {
            String out = new String(proc.getInputStream().readAllBytes());
            throw new IOException("Failed to clone rules repo: " + out);
        }
    }

    private void pullLatest(Path repoDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "pull", "--ff-only")
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.waitFor(2, TimeUnit.MINUTES);
    }

    private String injectCredentials(String url) {
        String gitUsername = settings.get("git.username", "");
        String gitPassword = settings.getSecret("git.password");
        if (gitUsername.isBlank() || gitPassword.isBlank()) {
            return url;
        }
        if (url.startsWith("https://")) {
            return url.replace("https://",
                    "https://" + gitUsername + ":" + gitPassword + "@");
        }
        return url;
    }
}
