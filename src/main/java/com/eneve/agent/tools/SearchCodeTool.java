package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.util.ProcessHelper;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provides grep-based code search over repositories.
 * Supports multiple search strategies:
 * 1. Search current workspace (if code available)
 * 2. Search specified repository (clone if needed)
 * 3. Context-aware search across related repositories (future enhancement)
 * 
 * Handles the case where no repository is initially cloned by lazily cloning
 * the target repository when needed.
 */
@ApplicationScoped
public class SearchCodeTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(SearchCodeTool.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 30;
    private static final long CLONE_TIMEOUT_MINUTES = 5;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int MAX_RESULTS = 100;

    @ConfigProperty(name = "git.username")
    String gitUsername;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @Inject
    GitPlatformService platformService;

    // Cache cloned repositories to avoid redundant clones in concurrent jobs
    private final Map<String, Path> repoCache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String pattern = (String) input.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "ERROR: 'pattern' parameter is required";
        }

        String searchPath = (String) input.getOrDefault("path", ".");
        String include = (String) input.get("include");
        String repoUrl = (String) input.get("repo");

        // Strategy 1: Search current workspace if it has a cloned repo
        if (repoUrl == null && workspace.hasClonedRepo()) {
            LOG.debugf("Searching current workspace for pattern: %s", pattern);
            return searchInWorkspace(workspace, pattern, searchPath, include);
        }

        // Strategy 2: Search specified repository (clone if needed)
        if (repoUrl != null && !repoUrl.isBlank()) {
            LOG.debugf("Searching repository %s for pattern: %s", repoUrl, pattern);
            return searchInRepository(repoUrl, pattern, searchPath, include);
        }

        // Strategy 3: Auto-discover and clone known repository from workspace metadata
        if (repoUrl == null && !workspace.hasClonedRepo()) {
            String ws = workspace.getMetadata("workspace");
            String repoSlug = workspace.getMetadata("repoSlug");
            
            if (ws != null && repoSlug != null) {
                String discoveredUrl = platformService.buildCloneUrl(ws, repoSlug);
                if (discoveredUrl != null) {
                    LOG.debugf("Auto-discovering repository %s/%s for pattern: %s", ws, repoSlug, pattern);
                    return searchInRepository(discoveredUrl, pattern, searchPath, include);
                } else {
                    return "ERROR: Cannot build clone URL for repository " + ws + "/" + repoSlug + 
                           ". Platform may not support auto-discovery.";
                }
            }
        }

        // Strategy 4: No workspace and no repo specified
        if (!workspace.hasClonedRepo()) {
            return "ERROR: No repository available for search. Either clone a repository first or specify a 'repo' parameter with the repository URL to search.";
        }

        // Fallback: search current workspace even if no .git directory
        LOG.debugf("Fallback: searching workspace directory for pattern: %s", pattern);
        return searchInWorkspace(workspace, pattern, searchPath, include);
    }

    /**
     * Search for pattern in the current workspace
     */
    private String searchInWorkspace(WorkspaceContext workspace, String pattern, String searchPath, String include) {
        try {
            workspace.resolve(searchPath);
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        }

        return executeGrepSearch(workspace.getRoot(), pattern, searchPath, include);
    }

    /**
     * Search in a specific repository, cloning it if necessary
     */
    private String searchInRepository(String repoUrl, String pattern, String searchPath, String include) {
        try {
            // Parse the repository URL to create a cache key
            RepoCoordinates coords = RepoCoordinates.parse(repoUrl);
            String cacheKey = coords.platform() + "_" + coords.organization() + "_" + coords.repository();
            
            Path repoPath = repoCache.computeIfAbsent(cacheKey, k -> {
                try {
                    return cloneRepository(coords);
                } catch (Exception e) {
                    LOG.errorf("Failed to clone repository %s: %s", repoUrl, e.getMessage());
                    return null;
                }
            });

            if (repoPath == null) {
                return "ERROR: Failed to clone repository: " + repoUrl;
            }

            // Validate search path within cloned repository
            Path resolvedPath = repoPath.resolve(searchPath).normalize();
            if (!resolvedPath.startsWith(repoPath)) {
                return "ERROR: Path traversal blocked: " + searchPath;
            }

            if (!Files.exists(resolvedPath)) {
                return "ERROR: Search path does not exist: " + searchPath;
            }

            return executeGrepSearch(repoPath, pattern, searchPath, include);

        } catch (IllegalArgumentException e) {
            return "ERROR: Cannot parse repository URL: " + repoUrl + " (" + e.getMessage() + ")";
        } catch (Exception e) {
            LOG.errorf("Error searching repository %s: %s", repoUrl, e.getMessage());
            return "ERROR: Failed to search repository: " + e.getMessage();
        }
    }

    /**
     * Execute the actual grep search command
     */
    private String executeGrepSearch(Path rootPath, String pattern, String searchPath, String include) {
        StringBuilder cmd = new StringBuilder("grep -rn");
        if (include != null && !include.isBlank()) {
            cmd.append(" --include=").append(shellQuote(include));
        }
        cmd.append(" ").append(shellQuote(pattern));
        cmd.append(" ").append(shellQuote(searchPath));

        try {
            ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "sh", "-c", cmd.toString())
                    .directory(rootPath.toFile())
                    .redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                return "ERROR: search_code timed out after " + COMMAND_TIMEOUT_SECONDS + " seconds";
            }

            int exitCode = proc.exitValue();
            if (exitCode == 1 && output.isBlank()) {
                return "No matches found for pattern: " + pattern;
            }
            if (exitCode > 1) {
                return "ERROR: grep exited with code " + exitCode + "\n" + output;
            }

            return formatOutput(output, pattern);
        } catch (IOException | InterruptedException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Clone a repository to a temporary directory for searching
     */
    private Path cloneRepository(RepoCoordinates coords) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("search-repo-" + coords.repository() + "-");
        LOG.infof("Cloning repository for search: %s into %s", coords.repoWebUrl(), tempDir);

        String authenticatedUrl = coords.httpsCloneUrl(gitUsername, gitPassword);
        
        // Use shallow clone for search purposes (faster)
        ProcessBuilder pb = ProcessHelper.cleanBuilder(null, 
                "git", "clone", "--depth", "1", authenticatedUrl, ".")
                .directory(tempDir.toFile())
                .redirectErrorStream(true);

        Process proc = pb.start();
        boolean finished = proc.waitFor(CLONE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (!finished) {
            proc.destroyForcibly();
            cleanupDirectory(tempDir);
            throw new IOException("Git clone timed out after " + CLONE_TIMEOUT_MINUTES + " minutes");
        }

        if (proc.exitValue() != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            cleanupDirectory(tempDir);
            throw new IOException("Git clone failed (exit " + proc.exitValue() + "): " + output);
        }

        LOG.infof("Successfully cloned repository for search: %s", coords.repoWebUrl());
        return tempDir;
    }

    /**
     * Format search output with line limits and truncation
     */
    private String formatOutput(String output, String pattern) {
        if (output.length() > MAX_OUTPUT_CHARS) {
            long lineCount = output.lines().count();
            output = output.substring(0, MAX_OUTPUT_CHARS);
            // Trim to the last complete line
            int lastNewline = output.lastIndexOf('\n');
            if (lastNewline > 0) {
                output = output.substring(0, lastNewline);
            }
            output += "\n... [output truncated — " + lineCount + " total matches, showing first "
                    + output.lines().count() + "]";
        }

        // Count total matches for summary
        long matchCount = output.lines().count();
        if (matchCount == 0) {
            return "No matches found for pattern: " + pattern;
        }

        String summary = String.format("Found %d matches for pattern: %s\n\n", matchCount, pattern);
        return summary + output;
    }

    /**
     * Clean up temporary directory
     */
    private void cleanupDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
                }
            }
        } catch (IOException e) {
            LOG.warnf("Failed to cleanup temporary directory %s: %s", dir, e.getMessage());
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Cleanup cached repositories on shutdown
     */
    public void shutdown() {
        for (Path repoPath : repoCache.values()) {
            if (repoPath != null) {
                cleanupDirectory(repoPath);
            }
        }
        repoCache.clear();
    }
}