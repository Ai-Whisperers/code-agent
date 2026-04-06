package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class WriteFileTool implements ToolExecutor {

    @Inject
    GuardrailConfig guardrails;

    @Override
    public String name() {
        return "write_file";
    }

    @Override public boolean isReadOnly()    { return false; }
    @Override public boolean isDestructive() { return true;  }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String path = (String) input.get("path");
        String content = (String) input.get("content");
        if (path == null || path.isBlank()) {
            return "ERROR: 'path' parameter is required";
        }
        if (content == null) {
            return "ERROR: 'content' parameter is required";
        }

        for (String blocked : guardrails.getBlockedPaths()) {
            if (path.startsWith(blocked) || path.equals(blocked)) {
                return "ERROR: Write blocked to protected path: " + blocked;
            }
        }

        try {
            Path resolved = workspace.resolve(path);
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content);
            return "OK: Written " + content.length() + " characters to " + path;
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        } catch (IOException e) {
            return "ERROR: Failed to write file: " + e.getMessage();
        }
    }
}
