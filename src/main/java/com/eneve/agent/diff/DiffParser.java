package com.eneve.agent.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-dependency parser for unified diff output produced by {@code git diff}.
 * Extracts file paths, hunk ranges, and per-line new-side line numbers.
 */
public final class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    private DiffParser() {}

    /**
     * Parse a unified diff string into a list of per-file structures
     * with resolved new-side line numbers.
     */
    public static List<ParsedDiffFile> parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return List.of();
        }

        List<ParsedDiffFile> files = new ArrayList<>();
        String currentPath = null;
        List<DiffHunk> currentHunks = new ArrayList<>();
        List<DiffLine> currentLines = null;
        int newLineNo = 0;
        int hunkNewStart = 0;
        int hunkNewCount = 0;

        for (String rawLine : unifiedDiff.split("\n", -1)) {

            if (rawLine.startsWith("diff --git ")) {
                // Flush previous file
                flushHunk(currentHunks, currentLines, hunkNewStart, hunkNewCount);
                flushFile(files, currentPath, currentHunks);
                currentPath = null;
                currentHunks = new ArrayList<>();
                currentLines = null;
                newLineNo = 0;
                continue;
            }

            if (rawLine.startsWith("+++ ")) {
                String path = rawLine.substring(4).trim();
                if (path.startsWith("b/")) {
                    path = path.substring(2);
                }
                if (!"/dev/null".equals(path)) {
                    currentPath = path;
                }
                continue;
            }

            if (rawLine.startsWith("--- ")) {
                if (currentPath == null) {
                    String path = rawLine.substring(4).trim();
                    if (path.startsWith("a/")) {
                        path = path.substring(2);
                    }
                    if (!"/dev/null".equals(path)) {
                        currentPath = path;
                    }
                }
                continue;
            }

            Matcher hunkMatcher = HUNK_HEADER.matcher(rawLine);
            if (hunkMatcher.find()) {
                flushHunk(currentHunks, currentLines, hunkNewStart, hunkNewCount);
                hunkNewStart = Integer.parseInt(hunkMatcher.group(1));
                hunkNewCount = hunkMatcher.group(2) != null
                        ? Integer.parseInt(hunkMatcher.group(2))
                        : 1;
                currentLines = new ArrayList<>();
                newLineNo = hunkNewStart;
                continue;
            }

            if (currentLines == null) {
                continue;
            }

            if (rawLine.startsWith("+")) {
                currentLines.add(new DiffLine(
                        DiffLine.Type.ADDED, newLineNo, rawLine.substring(1)));
                newLineNo++;
            } else if (rawLine.startsWith("-")) {
                currentLines.add(new DiffLine(
                        DiffLine.Type.REMOVED, -1, rawLine.substring(1)));
            } else if (rawLine.startsWith(" ")) {
                currentLines.add(new DiffLine(
                        DiffLine.Type.CONTEXT, newLineNo, rawLine.substring(1)));
                newLineNo++;
            } else if (rawLine.startsWith("\\")) {
                // "\ No newline at end of file" — skip
            }
        }

        flushHunk(currentHunks, currentLines, hunkNewStart, hunkNewCount);
        flushFile(files, currentPath, currentHunks);
        return List.copyOf(files);
    }

    private static void flushHunk(List<DiffHunk> hunks, List<DiffLine> lines,
                                  int newStart, int newCount) {
        if (lines != null && !lines.isEmpty()) {
            hunks.add(new DiffHunk(newStart, newCount, List.copyOf(lines)));
        }
    }

    private static void flushFile(List<ParsedDiffFile> files, String path,
                                  List<DiffHunk> hunks) {
        if (path != null && !hunks.isEmpty()) {
            files.add(new ParsedDiffFile(path, List.copyOf(hunks)));
        }
    }
}
