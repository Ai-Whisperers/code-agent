package com.eneve.agent.linter;

import java.nio.file.Path;

/**
 * Shared helpers for linter implementations.
 */
public final class LinterUtils {

    private LinterUtils() {}

    static String toRelativePath(String absolutePath, Path workspaceRoot) {
        try {
            Path abs = Path.of(absolutePath);
            if (abs.startsWith(workspaceRoot)) {
                return workspaceRoot.relativize(abs).toString();
            }
        } catch (Exception ignored) { }
        return absolutePath;
    }

    static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 2000 ? text.substring(0, 2000) + "..." : text;
    }
}
