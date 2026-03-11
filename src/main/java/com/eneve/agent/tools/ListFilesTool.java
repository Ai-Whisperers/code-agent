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

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String directory = (String) input.getOrDefault("directory", ".");
        try {
            Path resolved = workspace.resolve(directory);
            if (!Files.exists(resolved)) {
                return "ERROR: Directory not found: " + directory;
            }
            if (!Files.isDirectory(resolved)) {
                return "ERROR: Path is not a directory: " + directory;
            }
            try (Stream<Path> entries = Files.walk(resolved, 3)) {
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
