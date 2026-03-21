package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.eneve.agent.agent.QualityReport.TestPresenceSection;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Language-agnostic test presence checker.
 *
 * <p>Walks a repository root, classifies every recognized source file as either a
 * production source file or a test file, and returns a {@link TestPresenceSection}
 * with the ratio {@code testFiles / max(1, sourceFiles)}.
 *
 * <p>Supported languages and their test-detection heuristics:
 * <ul>
 *   <li><b>Java</b>   — anything under {@code src/test/} is a test file</li>
 *   <li><b>C#</b>     — {@code *.cs} files whose path contains a {@code Test(s)/} segment
 *                       or whose name contains {@code Test} / {@code Spec}</li>
 *   <li><b>TypeScript / JavaScript</b> — {@code *.test.*}, {@code *.spec.*}, or inside
 *                       {@code __tests__/} / {@code tests/} directories</li>
 *   <li><b>Python</b> — files named {@code test_*.py} / {@code *_test.py}, or inside
 *                       a {@code test/} / {@code tests/} directory</li>
 *   <li><b>Go</b>     — files ending in {@code _test.go}</li>
 * </ul>
 *
 * <p>Generated / vendored directories ({@code node_modules}, {@code target}, {@code build},
 * {@code vendor}, {@code .git}, etc.) are skipped entirely.
 */
@ApplicationScoped
public class TestPresenceChecker {

    private static final Logger LOG = Logger.getLogger(TestPresenceChecker.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".gradle",
            "bin", "obj", "dist", "out", ".next", ".nuxt", "vendor",
            ".idea", ".vscode", "__pycache__", ".pytest_cache", "coverage");

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Scans the repository at {@code root} and returns a test-presence snapshot.
     *
     * @param root     repository root directory
     * @param workspace logical workspace name (for logging)
     * @param repoSlug  repository slug (for logging)
     */
    public TestPresenceSection check(Path root, String workspace, String repoSlug) {
        int[] sourceCount = {0};
        int[] testCount   = {0};
        Set<String> languages = new TreeSet<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel  = root.relativize(file).toString().replace('\\', '/');
                    String name = file.getFileName().toString();

                    FileRole role = classify(rel, name, languages);
                    if (role == FileRole.TEST)   testCount[0]++;
                    if (role == FileRole.SOURCE) sourceCount[0]++;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf("TestPresenceChecker: error walking %s/%s: %s", workspace, repoSlug, e.getMessage());
        }

        double ratio = sourceCount[0] == 0 && testCount[0] == 0
                ? 0.0
                : Math.min(1.0, (double) testCount[0] / Math.max(1, sourceCount[0]));

        List<String> langList = new ArrayList<>(languages);

        LOG.infof("TestPresenceChecker: %s/%s — source=%d test=%d ratio=%.2f languages=%s",
                workspace, repoSlug, sourceCount[0], testCount[0], ratio, langList);

        return new TestPresenceSection(sourceCount[0], testCount[0], ratio, langList);
    }

    // ─── Classification ───────────────────────────────────────────────────

    private enum FileRole { SOURCE, TEST, IGNORE }

    /**
     * Classifies a single file into SOURCE, TEST, or IGNORE.
     * Also adds the detected language into {@code languages}.
     */
    private FileRole classify(String rel, String name, Set<String> languages) {
        String lower = rel.toLowerCase();

        // ── Java ──────────────────────────────────────────────────────
        if (name.endsWith(".java")) {
            languages.add("Java");
            // Maven / Gradle convention: src/test/ tree = test code
            if (lower.contains("/test/") || lower.startsWith("test/")) return FileRole.TEST;
            return FileRole.SOURCE;
        }

        // ── C# ────────────────────────────────────────────────────────
        if (name.endsWith(".cs")) {
            if (name.contains(".AssemblyInfo") || name.endsWith(".Designer.cs")
                    || name.endsWith(".g.cs")) return FileRole.IGNORE;
            languages.add("C#");
            if (lower.contains("/tests/") || lower.contains("/test/")
                    || containsWordIgnoreCase(name, "test") || containsWordIgnoreCase(name, "spec")) {
                return FileRole.TEST;
            }
            return FileRole.SOURCE;
        }

        // ── TypeScript / JavaScript ───────────────────────────────────
        if (name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".js") || name.endsWith(".jsx")) {
            // Skip declaration files and generated files
            if (name.endsWith(".d.ts") || name.endsWith(".min.js")
                    || lower.contains(".generated.")) return FileRole.IGNORE;
            languages.add(name.endsWith(".ts") || name.endsWith(".tsx") ? "TypeScript" : "JavaScript");
            if (name.matches(".*\\.(test|spec)\\.(ts|tsx|js|jsx)$")
                    || lower.contains("/__tests__/")
                    || lower.contains("/tests/")
                    || lower.matches(".*/test/.*\\.(ts|tsx|js|jsx)$")) {
                return FileRole.TEST;
            }
            return FileRole.SOURCE;
        }

        // ── Python ────────────────────────────────────────────────────
        if (name.endsWith(".py")) {
            if (name.equals("setup.py") || name.equals("conftest.py")
                    || name.startsWith("__")) return FileRole.IGNORE;
            languages.add("Python");
            if (name.startsWith("test_") || name.endsWith("_test.py")
                    || lower.contains("/tests/") || lower.contains("/test/")) {
                return FileRole.TEST;
            }
            return FileRole.SOURCE;
        }

        // ── Go ────────────────────────────────────────────────────────
        if (name.endsWith(".go")) {
            languages.add("Go");
            if (name.endsWith("_test.go")) return FileRole.TEST;
            return FileRole.SOURCE;
        }

        // ── PHP ───────────────────────────────────────────────────────
        if (name.endsWith(".php")) {
            languages.add("PHP");
            // PHPUnit conventions: files named *Test.php, or inside tests/ / test/ directories
            if (lower.contains("/tests/") || lower.contains("/test/")
                    || containsWordIgnoreCase(name, "test") || containsWordIgnoreCase(name, "spec")) {
                return FileRole.TEST;
            }
            return FileRole.SOURCE;
        }

        return FileRole.IGNORE;
    }

    /** True if {@code str} contains {@code word} regardless of case boundaries. */
    private static boolean containsWordIgnoreCase(String str, String word) {
        return str.toLowerCase().contains(word.toLowerCase());
    }
}
