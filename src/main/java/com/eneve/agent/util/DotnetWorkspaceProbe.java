package com.eneve.agent.util;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for detecting .NET workspaces and locating entry-point
 * project / solution files.
 *
 * <p>Replaces the four independent {@code hasDotnetProject} methods that previously
 * lived in {@code BuildValidator}, {@code AgentPromptBuilder}, {@code DotnetFormatLinter},
 * and {@code DotnetCoverageReporter}.
 */
public final class DotnetWorkspaceProbe {

    private static final Logger LOG = Logger.getLogger(DotnetWorkspaceProbe.class);

    /** Directories skipped during shallow walks. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "bin", "obj", ".git", "node_modules", ".vs", "packages",
            "target", "build", "dist", ".idea", ".vscode");

    /** Max directory depth for {@link #findSlnFiles} and {@link #findCsprojFiles}. */
    private static final int MAX_DEPTH = 3;

    private DotnetWorkspaceProbe() {
    }

    // ─── Root-level detection ────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the workspace root contains at least one of the
     * well-known .NET project markers <em>at the root level only</em> (no recursive
     * walk). Recognised markers:
     * <ul>
     *   <li>{@code Directory.Build.props}</li>
     *   <li>{@code Directory.Build.targets}</li>
     *   <li>{@code global.json} (SDK-style)</li>
     *   <li>Any {@code *.sln}, {@code *.csproj}, {@code *.fsproj}, or {@code *.vbproj} file</li>
     * </ul>
     */
    public static boolean hasDotnetAtRoot(Path root) {
        if (root == null) return false;
        if (Files.exists(root.resolve("Directory.Build.props"))) return true;
        if (Files.exists(root.resolve("Directory.Build.targets"))) return true;
        if (Files.exists(root.resolve("global.json"))) return true;
        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase())
                    .anyMatch(name -> name.endsWith(".sln") || name.endsWith(".csproj")
                            || name.endsWith(".fsproj") || name.endsWith(".vbproj"));
        } catch (IOException e) {
            LOG.debugf("DotnetWorkspaceProbe.hasDotnetAtRoot: cannot list %s: %s", root, e.getMessage());
            return false;
        }
    }

    // ─── Shallow walk helpers ────────────────────────────────────────────────────

    /**
     * Walks up to {@value #MAX_DEPTH} directory levels below {@code root} (skipping
     * common build-output and tooling directories) and returns all {@code *.sln} files
     * found.
     *
     * <p>The root level is included in the walk (depth 0).
     */
    public static List<Path> findSlnFiles(Path root) {
        return findByExtension(root, ".sln");
    }

    /**
     * Walks up to {@value #MAX_DEPTH} directory levels below {@code root} and returns
     * all {@code *.csproj} files found.
     */
    public static List<Path> findCsprojFiles(Path root) {
        return findByExtension(root, ".csproj");
    }

    // ─── Test-command resolution ─────────────────────────────────────────────────

    /**
     * Resolves the most appropriate {@code dotnet test} command for the workspace.
     *
     * <p>Decision tree (first match wins):
     * <ol>
     *   <li>Root has .NET markers ({@link #hasDotnetAtRoot}) → {@code dotnet test}</li>
     *   <li>Exactly one {@code *.sln} found within depth {@value #MAX_DEPTH} →
     *       {@code dotnet test <path-to-sln>}</li>
     *   <li>Multiple {@code *.sln} files found → {@code dotnet test} at root + warning
     *       (multi-solution repos are ambiguous)</li>
     *   <li>No {@code *.sln} but exactly one {@code *.csproj} within depth
     *       {@value #MAX_DEPTH} → {@code dotnet test <path-to-csproj>}</li>
     *   <li>Otherwise → {@code dotnet test} (safe fallback)</li>
     * </ol>
     *
     * @return a {@code dotnet test} command string, never {@code null}
     */
    public static String resolveDotnetTestCommand(Path root) {
        if (hasDotnetAtRoot(root)) {
            return "dotnet test";
        }

        List<Path> slnFiles = findSlnFiles(root);
        if (slnFiles.size() == 1) {
            return "dotnet test " + root.relativize(slnFiles.get(0));
        }
        if (slnFiles.size() > 1) {
            LOG.warnf("DotnetWorkspaceProbe: multiple .sln files found under %s — falling back to 'dotnet test' at root", root);
            return "dotnet test";
        }

        List<Path> csprojFiles = findCsprojFiles(root);
        if (csprojFiles.size() == 1) {
            return "dotnet test " + root.relativize(csprojFiles.get(0));
        }

        return "dotnet test";
    }

    // ─── Private helpers ─────────────────────────────────────────────────────────

    private static List<Path> findByExtension(Path root, String extension) {
        List<Path> results = new ArrayList<>();
        if (root == null) return results;
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase().endsWith(extension)) {
                        results.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.debugf("DotnetWorkspaceProbe.findByExtension(%s): walk failed: %s", extension, e.getMessage());
        }
        return results;
    }
}
