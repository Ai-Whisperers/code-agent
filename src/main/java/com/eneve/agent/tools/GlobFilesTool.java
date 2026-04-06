package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Finds files under the workspace by glob pattern.
 *
 * <p>Fills the gap between {@code list_files} (directory walk) and {@code search_code}
 * (content grep): finds files by name pattern rather than by content.
 *
 * <p>Mirrors the behaviour of claude-code's {@code GlobTool}:
 * <ul>
 *   <li>Results sorted by last-modified time (most-recently modified first)</li>
 *   <li>Capped at 100 results with a truncation note</li>
 *   <li>Paths relativized to the workspace root to save tokens</li>
 * </ul>
 */
@ApplicationScoped
public class GlobFilesTool implements ToolExecutor {

    private static final int MAX_RESULTS = 100;

    @Override
    public String name() {
        return "glob_files";
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

        String searchDir = (String) input.getOrDefault("path", ".");

        Path root = workspace.getRoot();
        Path searchRoot;
        try {
            searchRoot = workspace.resolve(searchDir);
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        }

        if (!Files.exists(searchRoot)) {
            return "ERROR: Directory not found: " + searchDir;
        }
        if (!Files.isDirectory(searchRoot)) {
            return "ERROR: Path is not a directory: " + searchDir;
        }

        // FileSystem.getPathMatcher requires the "glob:" scheme prefix.
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            return "ERROR: Invalid glob pattern '" + pattern + "': " + e.getMessage();
        }

        List<Path> matches = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> walk = Files.walk(searchRoot)) {
            List<Path> candidates = walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        // Match against the path relative to the search root so that
                        // patterns like "**/*.java" work regardless of where we started.
                        Path rel = searchRoot.relativize(p);
                        return matcher.matches(rel);
                    })
                    .sorted(Comparator.comparing(GlobFilesTool::mtimeOrEpoch).reversed())
                    .collect(java.util.stream.Collectors.toList());

            if (candidates.size() > MAX_RESULTS) {
                truncated = true;
                matches.addAll(candidates.subList(0, MAX_RESULTS));
            } else {
                matches.addAll(candidates);
            }
        } catch (IOException e) {
            return "ERROR: Failed to search directory: " + e.getMessage();
        }

        if (matches.isEmpty()) {
            return "No files found matching pattern: " + pattern;
        }

        StringBuilder sb = new StringBuilder();
        for (Path p : matches) {
            sb.append(root.relativize(p)).append('\n');
        }
        if (truncated) {
            sb.append("(Results truncated, consider a more specific path or pattern.)");
        } else {
            // Remove trailing newline
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }

        return sb.toString();
    }

    private static FileTime mtimeOrEpoch(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
