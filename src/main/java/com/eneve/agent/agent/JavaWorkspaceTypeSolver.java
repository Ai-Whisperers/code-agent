package com.eneve.agent.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.util.JavaParserConfig;
import com.eneve.agent.util.JdkResolver;
import com.eneve.agent.util.ProcessHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Builds a {@link CombinedTypeSolver} and {@link JavaParser} with symbol resolution for a cloned workspace.
 */
@ApplicationScoped
public class JavaWorkspaceTypeSolver {

    private static final Logger LOG = Logger.getLogger(JavaWorkspaceTypeSolver.class);

    /** Cap to keep JVM heap and resolution time bounded. */
    private static final int MAX_JAR_TYPE_SOLVERS = 400;

    private static final long MAVEN_CLASSPATH_TIMEOUT_SEC = 120;

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git", "target", "build", "node_modules", ".gradle", "bin", "out", ".idea",
            "dist", "vendor");

    @Inject
    SettingsService settings;

    /**
     * Creates a {@link JavaParser} configured with JAVA_21 and a symbol resolver for the given workspace.
     */
    public JavaParser createJavaParser(Path workspaceRoot) {
        CombinedTypeSolver solver = buildCombinedSolver(workspaceRoot);
        ParserConfiguration config = JavaParserConfig.java21BaseConfiguration();
        config.setSymbolResolver(new JavaSymbolSolver(solver));
        return new JavaParser(config);
    }

    CombinedTypeSolver buildCombinedSolver(Path workspaceRoot) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver());

        List<Path> sourceRoots;
        try {
            sourceRoots = discoverJavaSourceRoots(workspaceRoot);
        } catch (IOException e) {
            LOG.warnf("Failed to discover Java source roots under %s: %s", workspaceRoot, e.getMessage());
            sourceRoots = List.of();
        }
        for (Path root : sourceRoots) {
            try {
                combined.add(new JavaParserTypeSolver(root));
            } catch (Exception e) {
                LOG.debugf("Skipping JavaParserTypeSolver for %s: %s", root, e.getMessage());
            }
        }

        if (sourceRoots.isEmpty()) {
            LOG.debugf("No src/*/java directories under %s — source-only resolution may be weak", workspaceRoot);
        }

        if (Files.exists(workspaceRoot.resolve("pom.xml"))
                && Boolean.parseBoolean(settings.get("code-graph.java.maven-classpath.enabled", "true"))) {
            addMavenDependencyJars(workspaceRoot, combined);
        }

        return combined;
    }

    static List<Path> discoverJavaSourceRoots(Path workspaceRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return roots;
        }
        try (Stream<Path> walk = Files.walk(workspaceRoot)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("java"))
                    .filter(JavaWorkspaceTypeSolver::isUnderSrcTree)
                    .filter(p -> !isUnderSkippedAncestor(p, workspaceRoot))
                    .forEach(roots::add);
        }
        roots.sort(Comparator.naturalOrder());
        return roots;
    }

    private static boolean isUnderSrcTree(Path javaDir) {
        Path parent = javaDir.getParent();
        if (parent == null) return false;
        Path grandParent = parent.getParent();
        return grandParent != null && grandParent.getFileName().toString().equals("src");
    }

    private static boolean isUnderSkippedAncestor(Path dir, Path workspaceRoot) {
        Path rel = workspaceRoot.relativize(dir);
        for (Path part : rel) {
            String name = part.getFileName().toString();
            if (SKIP_DIR_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addMavenDependencyJars(Path workspaceRoot, CombinedTypeSolver combined) {
        Path cpFile;
        try {
            cpFile = Files.createTempFile("code-agent-mvn-cp-", ".txt");
        } catch (IOException e) {
            LOG.warnf("Could not create temp file for Maven classpath: %s", e.getMessage());
            return;
        }
        try {
            String mavenHome = settings.get("build.maven-home", "").trim();
            String mvn = ProcessHelper.mvn(workspaceRoot, mavenHome.isEmpty() ? null : mavenHome);
            String javaHome = JdkResolver.resolveForWorkspace(workspaceRoot);

            List<String> cmd = new ArrayList<>();
            cmd.add(mvn);
            cmd.add("-q");
            cmd.add("dependency:build-classpath");
            cmd.add("-DincludeScope=compile");
            cmd.add("-Dmdep.outputFile=" + cpFile.toAbsolutePath());

            ProcessBuilder pb = ProcessHelper.cleanBuilderWithMaven(
                    javaHome != null && !javaHome.isBlank() ? javaHome : null,
                    mavenHome.isEmpty() ? null : mavenHome,
                    cmd.toArray(String[]::new));
            pb.directory(workspaceRoot.toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(MAVEN_CLASSPATH_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                LOG.warnf("Maven dependency:build-classpath timed out after %ds for %s",
                        MAVEN_CLASSPATH_TIMEOUT_SEC, workspaceRoot);
                return;
            }
            if (proc.exitValue() != 0) {
                LOG.debugf("Maven dependency:build-classpath exit %d for %s — using source-only type solver",
                        proc.exitValue(), workspaceRoot);
                return;
            }
            String cp = Files.readString(cpFile).trim();
            if (cp.isEmpty()) {
                return;
            }
            int added = 0;
            for (String entry : cp.split(System.getProperty("path.separator"))) {
                if (entry.isBlank()) continue;
                Path jar = Path.of(entry.trim());
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                try {
                    combined.add(new JarTypeSolver(jar));
                    added++;
                    if (added >= MAX_JAR_TYPE_SOLVERS) {
                        LOG.warnf("Reached MAX_JAR_TYPE_SOLVERS (%d) for %s — remaining jars skipped",
                                MAX_JAR_TYPE_SOLVERS, workspaceRoot);
                        break;
                    }
                } catch (Exception e) {
                    LOG.debugf("Skipping jar for type solver: %s: %s", jar, e.getMessage());
                }
            }
            if (added > 0) {
                LOG.debugf("Added %d JarTypeSolver(s) for %s", added, workspaceRoot);
            }
        } catch (Exception e) {
            LOG.debugf("Maven classpath not available for %s: %s", workspaceRoot, e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(cpFile);
            } catch (IOException ignored) {
            }
        }
    }
}
