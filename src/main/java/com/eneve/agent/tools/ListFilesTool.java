package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ListFilesTool implements ToolExecutor {

    private static final int MAX_ENTRIES = 500;
    private static final int DEFAULT_DEPTH = 3;
    private static final int MAX_DEPTH = 5;

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String directory = (String) input.getOrDefault("directory", ".");
        int depth = DEFAULT_DEPTH;
        Object depthVal = input.get("depth");
        if (depthVal instanceof Number n) {
            depth = Math.min(Math.max(1, n.intValue()), MAX_DEPTH);
        }
        try {
            Path resolved = workspace.resolve(directory);
            if (!Files.exists(resolved)) {
                return "ERROR: Directory not found: " + directory;
            }
            if (!Files.isDirectory(resolved)) {
                return "ERROR: Path is not a directory: " + directory;
            }
            try (Stream<Path> entries = Files.walk(resolved, depth)) {
                String listing = entries
                        .limit(MAX_ENTRIES)
                        .map(p -> workspace.getRoot().relativize(p).toString()
                                + (Files.isDirectory(p) ? "/" : ""))
                        .collect(Collectors.joining("\n"));
                return listing.isEmpty() ? "(empty directory)" : listing;
            }
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        } catch (IOException e) {
            return "ERROR: Failed to list directory: " + e.getMessage();
        }
    }
}
