package com.eneve.agent.diff;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Formats parsed diffs into LLM-friendly annotated text and builds
 * the commentable-lines index used for post-review validation.
 */
public final class DiffFormatter {

    private DiffFormatter() {}

    /**
     * Produce a line-annotated diff where every line carries its new-side
     * line number explicitly. Added lines are marked with {@code +}.
     * Removed lines show {@code -} and no line number.
     *
     * <pre>
     * --- src/main/java/com/example/Foo.java ---
     *   38 |  public void process(String input) {
     *   39 |      String query = "SELECT * FROM users WHERE name = '"
     *   40+|          + input
     *   41 |          + "'";
     *      -|      oldStatement();
     * </pre>
     */
    public static String toAnnotated(List<ParsedDiffFile> files) {
        StringBuilder sb = new StringBuilder();
        for (ParsedDiffFile file : files) {
            sb.append("--- ").append(file.path()).append(" ---\n");
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    switch (line.type()) {
                        case ADDED -> sb.append(String.format("%5d+| %s\n",
                                line.newLineNo(), line.content()));
                        case REMOVED -> sb.append(String.format("     -| %s\n",
                                line.content()));
                        case CONTEXT -> sb.append(String.format("%5d | %s\n",
                                line.newLineNo(), line.content()));
                    }
                }
                sb.append("  ...\n");
            }
        }
        return sb.toString();
    }

    /**
     * Build a map from file path to the sorted set of new-side line numbers
     * that appear in the diff (ADDED + CONTEXT lines). These are the lines
     * where Bitbucket will accept an inline comment.
     */
    public static Map<String, TreeSet<Integer>> buildCommentableLines(List<ParsedDiffFile> files) {
        Map<String, TreeSet<Integer>> result = new TreeMap<>();
        for (ParsedDiffFile file : files) {
            TreeSet<Integer> lines = result.computeIfAbsent(file.path(), k -> new TreeSet<>());
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.newLineNo() > 0) {
                        lines.add(line.newLineNo());
                    }
                }
            }
        }
        return result;
    }

    /**
     * Snap a candidate line number to the nearest valid line in the set.
     * Returns the candidate itself if the set is empty or null.
     */
    public static int snapToNearest(TreeSet<Integer> validLines, int candidate) {
        if (validLines == null || validLines.isEmpty()) {
            return candidate;
        }
        if (validLines.contains(candidate)) {
            return candidate;
        }
        Integer floor = validLines.floor(candidate);
        Integer ceiling = validLines.ceiling(candidate);
        if (floor == null) return ceiling;
        if (ceiling == null) return floor;
        return (candidate - floor <= ceiling - candidate) ? floor : ceiling;
    }

    /**
     * Truncate the file list at file boundaries so the total annotated
     * output stays within {@code maxChars}. Returns a (possibly shorter)
     * sublist; never cuts mid-file.
     */
    public static List<ParsedDiffFile> truncateAtFileBoundary(List<ParsedDiffFile> files,
                                                              int maxChars) {
        int total = 0;
        for (int i = 0; i < files.size(); i++) {
            int fileLen = estimateAnnotatedLength(files.get(i));
            if (total + fileLen > maxChars && i > 0) {
                return files.subList(0, i);
            }
            total += fileLen;
        }
        return files;
    }

    private static int estimateAnnotatedLength(ParsedDiffFile file) {
        int len = file.path().length() + 10;
        for (DiffHunk hunk : file.hunks()) {
            len += 6; // "  ...\n"
            for (DiffLine line : hunk.lines()) {
                len += 9 + line.content().length();
            }
        }
        return len;
    }
}
