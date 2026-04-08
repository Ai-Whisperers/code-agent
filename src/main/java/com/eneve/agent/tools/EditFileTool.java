package com.eneve.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Applies a targeted string-replacement edit to a workspace file.
 *
 * <p>Safer and more token-efficient than {@code write_file} because the caller
 * only needs to supply the text to change rather than the entire file content.
 *
 * <p>Mirrors the key behaviours of claude-code's {@code FileEditTool}:
 * <ul>
 *   <li>Exact-match first, then curly-quote-normalised fallback</li>
 *   <li>Rejects ambiguous replacements (multiple matches) unless {@code replace_all=true}</li>
 *   <li>Staleness check: refuses to edit a file that has been modified since it was last read</li>
 *   <li>Trailing-newline strip when deleting a line ({@code new_string=""})</li>
 *   <li>File-size guard: rejects files larger than 10 MB</li>
 * </ul>
 */
@ApplicationScoped
public class EditFileTool implements ToolExecutor {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB

    // Curly-quote characters that LLMs may not reproduce faithfully.
    private static final String LEFT_DOUBLE  = "\u201C"; // "
    private static final String RIGHT_DOUBLE = "\u201D"; // "
    private static final String LEFT_SINGLE  = "\u2018"; // '
    private static final String RIGHT_SINGLE = "\u2019"; // '

    @Inject
    GuardrailConfig guardrails;

    @Override
    public String name() {
        return "edit_file";
    }

    @Override public boolean isReadOnly()    { return false; }
    @Override public boolean isDestructive() { return true;  }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String filePath  = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");

        if (filePath == null || filePath.isBlank()) {
            return "ERROR: 'file_path' parameter is required";
        }
        if (oldString == null) {
            return "ERROR: 'old_string' parameter is required";
        }
        if (newString == null) {
            return "ERROR: 'new_string' parameter is required";
        }

        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));

        // 1. old_string must differ from new_string.
        if (oldString.equals(newString)) {
            return "ERROR: 'old_string' and 'new_string' are identical — no change would be made";
        }

        // 2. Blocked paths.
        for (String blocked : guardrails.getBlockedPaths()) {
            if (filePath.startsWith(blocked) || filePath.equals(blocked)) {
                return "ERROR: Write blocked to protected path: " + blocked;
            }
        }

        Path resolved;
        try {
            resolved = workspace.resolve(filePath);
        } catch (SecurityException e) {
            return "ERROR: " + e.getMessage();
        }

        // 3. File-size guard (skip for new-file creation where the file does not exist yet).
        if (Files.exists(resolved)) {
            try {
                long size = Files.size(resolved);
                if (size > MAX_FILE_SIZE_BYTES) {
                    return String.format(
                            "ERROR: File too large to edit (%dMB). Maximum is 10MB.",
                            size / (1024 * 1024));
                }
            } catch (IOException e) {
                return "ERROR: Cannot stat file: " + e.getMessage();
            }
        }

        // 4. New-file creation: old_string="" on a non-existent file.
        if (oldString.isEmpty()) {
            if (!Files.exists(resolved)) {
                return createFile(workspace, resolved, newString);
            }
            // File exists — only allowed when the file is also empty.
            try {
                String existing = Files.readString(resolved);
                if (!existing.isBlank()) {
                    return "ERROR: Cannot create new file — file already exists with content";
                }
                return writeFile(workspace, resolved, newString, 0, newString.lines().count());
            } catch (IOException e) {
                return "ERROR: Failed to read existing file: " + e.getMessage();
            }
        }

        // 5. Read current content.
        String content;
        try {
            content = Files.readString(resolved);
        } catch (IOException e) {
            return "ERROR: File not found: " + filePath;
        }

        // 6. Staleness check.
        FileTime lastRead = workspace.getFileReadTime(resolved);
        if (lastRead == null) {
            return "ERROR: File has not been read yet. Read it first before editing.";
        }
        try {
            FileTime currentMtime = Files.getLastModifiedTime(resolved);
            if (currentMtime.compareTo(lastRead) > 0) {
                return "ERROR: File has been modified since it was last read. Read it again before editing.";
            }
        } catch (IOException e) {
            return "ERROR: Cannot stat file: " + e.getMessage();
        }

        // 7. Quote-normalised matching.
        String actualOldString = findActualString(content, oldString);
        if (actualOldString == null) {
            return "ERROR: String to replace not found in file.\nString: " + oldString;
        }

        // 8. (already handled above — null means not found)

        // 9. Ambiguity check.
        if (!replaceAll) {
            int count = countOccurrences(content, actualOldString);
            if (count > 1) {
                return String.format(
                        "ERROR: Found %d matches of the string to replace, but replace_all is false. "
                        + "Set replace_all=true to replace all occurrences, or provide more surrounding "
                        + "context to uniquely identify the target.\nString: %s",
                        count, oldString);
            }
        }

        // 10 & 11. Apply replacement with optional trailing-newline strip on deletion.
        String effectiveOld = (newString.isEmpty() && content.contains(actualOldString + "\n"))
                ? actualOldString + "\n"
                : actualOldString;

        String updated = replaceAll
                ? content.replace(effectiveOld, newString)
                : replaceFirst(content, effectiveOld, newString);

        // 12. Write and update read timestamp.
        long oldLines = content.lines().count();
        long newLines = updated.lines().count();
        long added    = Math.max(0, newLines - oldLines);
        long removed  = Math.max(0, oldLines - newLines);

        return writeFile(workspace, resolved, updated, removed, added);
    }

    // --- helpers ---

    private String createFile(WorkspaceContext workspace, Path resolved, String content) {
        try {
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content);
            workspace.recordFileRead(resolved);
            return "OK: Created " + workspace.getRoot().relativize(resolved)
                    + " (" + content.lines().count() + " lines)";
        } catch (IOException e) {
            return "ERROR: Failed to create file: " + e.getMessage();
        }
    }

    private String writeFile(WorkspaceContext workspace, Path resolved,
                              String content, long linesRemoved, long linesAdded) {
        try {
            Files.writeString(resolved, content);
            workspace.recordFileRead(resolved);
            return String.format("OK: Edited %s (+%d -%d lines)",
                    workspace.getRoot().relativize(resolved), linesAdded, linesRemoved);
        } catch (IOException e) {
            return "ERROR: Failed to write file: " + e.getMessage();
        }
    }

    /**
     * Finds the actual character sequence in {@code fileContent} that matches
     * {@code searchString}, trying exact match first and then a curly-quote
     * normalised fallback.
     *
     * @return the actual substring present in the file, or {@code null} if not found
     */
    static String findActualString(String fileContent, String searchString) {
        // Exact match.
        if (fileContent.contains(searchString)) {
            return searchString;
        }

        // Normalised match: replace curly quotes with straight equivalents in both
        // the search string and the file, then find the index in the normalised file
        // and extract the original characters from that position.
        String normSearch = normalizeQuotes(searchString);
        String normFile   = normalizeQuotes(fileContent);

        int idx = normFile.indexOf(normSearch);
        if (idx != -1) {
            return fileContent.substring(idx, idx + searchString.length());
        }

        return null;
    }

    private static String normalizeQuotes(String s) {
        return s
                .replace(LEFT_DOUBLE,  "\"")
                .replace(RIGHT_DOUBLE, "\"")
                .replace(LEFT_SINGLE,  "'")
                .replace(RIGHT_SINGLE, "'");
    }

    /** Counts non-overlapping occurrences of {@code target} in {@code text}. */
    private static int countOccurrences(String text, String target) {
        int count = 0;
        int idx   = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    /** Replaces only the first occurrence of {@code target} in {@code text} with {@code replacement}. */
    private static String replaceFirst(String text, String target, String replacement) {
        int idx = text.indexOf(target);
        if (idx == -1) {
            return text;
        }
        return text.substring(0, idx) + replacement + text.substring(idx + target.length());
    }
}
