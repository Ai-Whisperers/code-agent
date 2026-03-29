package com.eneve.agent.diff;

import com.eneve.agent.model.DiffFileEntry;
import com.eneve.agent.model.DiffHunkEntry;
import com.eneve.agent.model.DiffLineEntry;
import com.eneve.agent.model.JobDiffResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff string into a {@link JobDiffResponse} suitable for the UI.
 *
 * <p>Unlike {@link DiffParser} (which produces {@link ParsedDiffFile} objects for the
 * AI review pipeline), this parser tracks per-line old/new line numbers and classifies
 * each line as {@code add}, {@code del}, or {@code ctx}.
 */
public final class JobDiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@(.*)$");

    private JobDiffParser() {}

    /**
     * Parses {@code rawDiff} and returns a structured response with per-file, per-hunk,
     * and per-line detail.
     *
     * @param sourceBranch the branch the diff originates from (for metadata)
     * @param targetBranch the branch the diff targets (for metadata)
     * @param rawDiff      raw unified-diff text; {@code null} or blank returns an empty response
     */
    public static JobDiffResponse parse(String sourceBranch, String targetBranch, String rawDiff) {
        List<DiffFileEntry> files = new ArrayList<>();

        if (rawDiff == null || rawDiff.isBlank()) {
            return new JobDiffResponse(sourceBranch, targetBranch, 0, 0, files);
        }

        String currentPath    = null;
        String currentOldPath = null;
        List<DiffHunkEntry> currentHunks  = null;
        List<DiffLineEntry> currentLines  = null;
        String currentHunkHeader = null;
        int oldLineNo = 0;
        int newLineNo = 0;

        for (String raw : rawDiff.split("\n", -1)) {

            if (raw.startsWith("diff --git ")) {
                flushHunk(currentHunks, currentLines, currentHunkHeader);
                flushFile(files, currentPath, currentOldPath, currentHunks);
                currentPath       = null;
                currentOldPath    = null;
                currentHunks      = new ArrayList<>();
                currentLines      = null;
                currentHunkHeader = null;
                oldLineNo         = 0;
                newLineNo         = 0;
                continue;
            }

            if (raw.startsWith("--- ")) {
                String path = raw.substring(4).trim();
                if (path.startsWith("a/")) path = path.substring(2);
                currentOldPath = "/dev/null".equals(path) ? null : path;
                continue;
            }

            if (raw.startsWith("+++ ")) {
                String path = raw.substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                if (!"/dev/null".equals(path)) currentPath = path;
                continue;
            }

            Matcher m = HUNK_HEADER.matcher(raw);
            if (m.find()) {
                flushHunk(currentHunks, currentLines, currentHunkHeader);
                oldLineNo         = Integer.parseInt(m.group(1));
                newLineNo         = Integer.parseInt(m.group(3));
                currentHunkHeader = raw.trim();
                currentLines      = new ArrayList<>();
                continue;
            }

            if (currentLines == null) continue;

            if (raw.startsWith("+")) {
                currentLines.add(new DiffLineEntry("add", 0, newLineNo, raw.substring(1)));
                newLineNo++;
            } else if (raw.startsWith("-")) {
                currentLines.add(new DiffLineEntry("del", oldLineNo, 0, raw.substring(1)));
                oldLineNo++;
            } else if (raw.startsWith(" ")) {
                currentLines.add(new DiffLineEntry("ctx", oldLineNo, newLineNo, raw.substring(1)));
                oldLineNo++;
                newLineNo++;
            }
        }

        flushHunk(currentHunks, currentLines, currentHunkHeader);
        flushFile(files, currentPath, currentOldPath, currentHunks);

        int totalAdditions = files.stream().mapToInt(DiffFileEntry::additions).sum();
        int totalDeletions = files.stream().mapToInt(DiffFileEntry::deletions).sum();
        return new JobDiffResponse(sourceBranch, targetBranch, totalAdditions, totalDeletions, files);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static void flushHunk(List<DiffHunkEntry> hunks, List<DiffLineEntry> lines, String header) {
        if (hunks != null && lines != null && !lines.isEmpty()) {
            hunks.add(new DiffHunkEntry(header != null ? header : "", List.copyOf(lines)));
        }
    }

    private static void flushFile(List<DiffFileEntry> files, String path, String oldPath,
                                   List<DiffHunkEntry> hunks) {
        if (hunks == null || hunks.isEmpty()) return;
        String effectivePath = path != null ? path : (oldPath != null ? oldPath : "unknown");
        String status        = path == null ? "removed" : (oldPath == null ? "added" : "modified");
        int additions = 0;
        int deletions = 0;
        for (DiffHunkEntry hunk : hunks) {
            for (DiffLineEntry line : hunk.lines()) {
                if ("add".equals(line.type())) additions++;
                else if ("del".equals(line.type())) deletions++;
            }
        }
        files.add(new DiffFileEntry(effectivePath, status, additions, deletions, List.copyOf(hunks)));
    }
}
