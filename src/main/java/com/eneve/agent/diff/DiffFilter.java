package com.eneve.agent.diff;

import java.util.List;
import java.util.Set;

/**
 * Filters parsed diff files to retain only human-reviewable source files.
 * <p>
 * Excluded categories:
 * <ul>
 *   <li>Coverage and test reports (cobertura.xml, jacoco.xml, surefire-reports, …)</li>
 *   <li>Dependency lock files (package-lock.json, yarn.lock, composer.lock, …)</li>
 *   <li>Build/compiled artifacts (*.class, *.jar, *.min.js, *.map, …)</li>
 *   <li>Generated output directories (target/, dist/, build/, .idea/, …)</li>
 * </ul>
 */
public final class DiffFilter {

    private DiffFilter() {}

    /** Exact filenames (case-insensitive) that are never worth reviewing. */
    private static final Set<String> BLOCKED_FILENAMES = Set.of(
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "composer.lock",
            "pipfile.lock",
            "poetry.lock",
            "gemfile.lock",
            "cargo.lock",
            "packages.lock.json"
    );

    /**
     * Substrings matched against the lowercased filename when the file extension
     * is {@code .xml}. Catches cobertura.xml, jacoco.xml, surefire-report.xml, etc.
     * without blocking all XML files.
     */
    private static final List<String> BLOCKED_XML_FILENAME_SUBSTRINGS = List.of(
            "cobertura",
            "jacoco",
            "clover",
            "coverage",
            "surefire-report",
            "failsafe-report",
            "test-result"
    );

    /** Lowercased path segments that indicate a generated or build-output directory. */
    private static final List<String> BLOCKED_PATH_SEGMENTS = List.of(
            "/target/surefire-reports/",
            "/target/failsafe-reports/",
            "/target/site/",
            "/build/reports/",
            "/build/test-results/",
            "/test-results/",
            "/test-reports/",
            "/.idea/",
            "/.vscode/"
    );

    /** Lowercased file suffixes that indicate binary or compiled output. */
    private static final List<String> BLOCKED_SUFFIXES = List.of(
            ".class",
            ".jar",
            ".war",
            ".ear",
            ".zip",
            ".tar",
            ".gz",
            ".tgz",
            ".pyc",
            ".pyo",
            ".min.js",
            ".min.css",
            ".map",
            ".iml"
    );

    /**
     * Returns only the files that are worth reviewing by a code-review agent.
     * The original list order is preserved.
     */
    public static List<ParsedDiffFile> filterReviewable(List<ParsedDiffFile> files) {
        return files.stream()
                .filter(DiffFilter::isReviewable)
                .toList();
    }

    static boolean isReviewable(ParsedDiffFile file) {
        String path = file.path();
        String lower = path.toLowerCase();
        String fileName = path.contains("/")
                ? path.substring(path.lastIndexOf('/') + 1).toLowerCase()
                : lower;

        if (BLOCKED_FILENAMES.contains(fileName)) {
            return false;
        }

        for (String suffix : BLOCKED_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return false;
            }
        }

        if (lower.endsWith(".xml")) {
            for (String keyword : BLOCKED_XML_FILENAME_SUBSTRINGS) {
                if (fileName.contains(keyword)) {
                    return false;
                }
            }
        }

        for (String segment : BLOCKED_PATH_SEGMENTS) {
            if (lower.contains(segment)) {
                return false;
            }
        }

        return true;
    }
}
