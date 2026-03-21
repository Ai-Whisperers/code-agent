package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReadFileTool implements ToolExecutor {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String path = (String) input.get("path");
        if (path == null || path.isBlank()) {
            return "ERROR: 'path' parameter is required";
        }
        try {
            Path resolved = workspace.resolve(path);
            if (!Files.exists(resolved)) {
                return "ERROR: File not found: " + path;
            }
            if (Files.isDirectory(resolved)) {
                return "ERROR: Path is a directory, not a file: " + path;
            }
            return Files.readString(resolved);
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        } catch (IOException e) {
            return "ERROR: Failed to read file: " + e.getMessage();
        }
    }
}
