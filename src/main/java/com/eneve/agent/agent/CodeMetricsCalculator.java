package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Calculates cyclomatic complexity (CC) metrics for Java source files
 * using the JavaParser AST.
 *
 * <p>CC definition used: base 1 per method/constructor, +1 for each decision point:
 * {@code if}, {@code for}, enhanced {@code for}, {@code while}, {@code do},
 * non-default {@code switch} case, {@code catch} block, {@code &&}, {@code ||},
 * and ternary {@code ?:}.
 */
@ApplicationScoped
public class CodeMetricsCalculator {

    /**
     * Shared, immutable parser configuration. Using an explicit JavaParser instance
     * (rather than StaticJavaParser) avoids the global-state race condition when parsing
     * files in parallel across multiple worker threads.
     */
    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private static final Logger LOG = Logger.getLogger(CodeMetricsCalculator.class);
    private static final long MAX_FILE_SIZE = 200 * 1024; // 200 KB
    private static final long MAX_SCAN_TIME_MS = 120_000;  // 2 minutes

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".gradle", "bin", "obj");

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Scans all Java source files under {@code root} and returns a snapshot of
     * cyclomatic complexity metrics per method.
     *
     * @param root      the repository root (cloned workspace)
     * @param workspace the workspace name (for snapshot metadata)
     * @param repoSlug  the repo slug (for snapshot metadata)
     * @param branch    the branch name being analysed
     * @param threshold methods with CC above this value are flagged as violators
     */
    public CodeMetricsSnapshot calculate(Path root, String workspace, String repoSlug,
                                         String branch, int threshold) {
        List<Path> javaFiles = findJavaFiles(root);

        LOG.infof("CodeMetrics: found %d Java files to analyse for %s/%s", javaFiles.size(), workspace, repoSlug);

        // Parse files in parallel using a bounded thread pool to speed up large repos.
        // JavaParser instances are created per-thread (StaticJavaParser delegates to a
        // thread-local parser), so concurrent parsing is safe.
        int parallelism = Math.min(Runtime.getRuntime().availableProcessors(), 8);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<MethodMetric> methods = new CopyOnWriteArrayList<>();
        AtomicInteger scanned = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>(javaFiles.size());
        for (Path file : javaFiles) {
            futures.add(pool.submit(() -> {
                if (System.currentTimeMillis() - startTime > MAX_SCAN_TIME_MS) {
                    return;
                }
                String relativePath = root.relativize(file).toString();
                try {
                    if (Files.size(file) > MAX_FILE_SIZE) {
                        LOG.debugf("CodeMetrics: skipping large file %s", relativePath);
                        return;
                    }
                    analyseFile(file, relativePath, methods);
                    scanned.incrementAndGet();
                } catch (Exception e) {
                    LOG.debugf("CodeMetrics: skipping %s — %s", relativePath, e.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            // Wait for at most the scan time limit; stragglers are abandoned
            pool.awaitTermination(MAX_SCAN_TIME_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("CodeMetrics: scan interrupted for %s/%s", workspace, repoSlug);
        }
        // Cancel any still-running tasks after the deadline
        futures.forEach(f -> f.cancel(true));

        LOG.infof("CodeMetrics: analysed %d files, found %d methods for %s/%s",
                scanned.get(), methods.size(), workspace, repoSlug);

        int methodsAbove = (int) methods.stream().filter(m -> m.cyclomaticComplexity() > threshold).count();
        double avg = methods.isEmpty() ? 0.0
                : methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).average().orElse(0.0);
        int max = methods.stream().mapToInt(MethodMetric::cyclomaticComplexity).max().orElse(0);

        return new CodeMetricsSnapshot(workspace, repoSlug, branch, Instant.now(),
                methods, methods.size(), methodsAbove, avg, max, threshold);
    }

    // ─── File walking ────────────────────────────────────────────────────

    private List<Path> findJavaFiles(Path root) {
        List<Path> files = new ArrayList<>();
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
                    if (file.toString().endsWith(".java")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf("CodeMetrics: error walking file tree: %s", e.getMessage());
        }
        return files;
    }

    // ─── AST analysis ────────────────────────────────────────────────────

    private void analyseFile(Path file, String relativePath, List<MethodMetric> out) throws Exception {
        CompilationUnit cu = new JavaParser(PARSER_CONFIG).parse(file)
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Parse returned empty result"));

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());

            cls.getMethods().forEach(method -> {
                int cc = computeCC(method);
                int lineStart = method.getBegin().map(p -> p.line).orElse(0);
                int lineEnd = method.getEnd().map(p -> p.line).orElse(0);
                out.add(new MethodMetric(
                        relativePath, className, method.getNameAsString(),
                        cc, lineEnd - lineStart + 1, method.getParameters().size(),
                        lineStart, lineEnd));
            });

            cls.getConstructors().forEach(ctor -> {
                int cc = computeCC(ctor);
                int lineStart = ctor.getBegin().map(p -> p.line).orElse(0);
                int lineEnd = ctor.getEnd().map(p -> p.line).orElse(0);
                out.add(new MethodMetric(
                        relativePath, className, "<init>",
                        cc, lineEnd - lineStart + 1, ctor.getParameters().size(),
                        lineStart, lineEnd));
            });
        });
    }

    /**
     * Computes cyclomatic complexity for a method declaration.
     * Base = 1; +1 per: if, for, forEach, while, do, non-default case,
     * catch, logical &&, logical ||, ternary ?:
     */
    private int computeCC(MethodDeclaration method) {
        int cc = 1;
        cc += method.findAll(IfStmt.class).size();
        cc += method.findAll(ForStmt.class).size();
        cc += method.findAll(ForEachStmt.class).size();
        cc += method.findAll(WhileStmt.class).size();
        cc += method.findAll(DoStmt.class).size();
        cc += method.findAll(CatchClause.class).size();
        cc += method.findAll(ConditionalExpr.class).size();
        cc += countNonDefaultCases(method.findAll(SwitchEntry.class));
        cc += countLogicalOperators(method.findAll(BinaryExpr.class));
        return cc;
    }

    private int computeCC(ConstructorDeclaration ctor) {
        int cc = 1;
        cc += ctor.findAll(IfStmt.class).size();
        cc += ctor.findAll(ForStmt.class).size();
        cc += ctor.findAll(ForEachStmt.class).size();
        cc += ctor.findAll(WhileStmt.class).size();
        cc += ctor.findAll(DoStmt.class).size();
        cc += ctor.findAll(CatchClause.class).size();
        cc += ctor.findAll(ConditionalExpr.class).size();
        cc += countNonDefaultCases(ctor.findAll(SwitchEntry.class));
        cc += countLogicalOperators(ctor.findAll(BinaryExpr.class));
        return cc;
    }

    private int countNonDefaultCases(List<SwitchEntry> entries) {
        return (int) entries.stream()
                .filter(e -> !e.getLabels().isEmpty())
                .count();
    }

    private int countLogicalOperators(List<BinaryExpr> exprs) {
        return (int) exprs.stream()
                .filter(e -> e.getOperator() == BinaryExpr.Operator.AND
                        || e.getOperator() == BinaryExpr.Operator.OR)
                .count();
    }

    // ─── Data model ──────────────────────────────────────────────────────

    /**
     * Complexity metrics for a single method or constructor.
     */
    public record MethodMetric(
            String filePath,
            String className,
            String methodName,
            int cyclomaticComplexity,
            int lineCount,
            int parameterCount,
            int lineStart,
            int lineEnd) {}

    /**
     * Aggregated complexity snapshot for a whole repository scan.
     */
    public record CodeMetricsSnapshot(
            String workspace,
            String repoSlug,
            String branch,
            Instant measuredAt,
            List<MethodMetric> methods,
            int totalMethods,
            int methodsAboveThreshold,
            double avgComplexity,
            int maxComplexity,
            int threshold) {

        /**
         * Methods that exceed the threshold, sorted by CC descending (worst offenders first).
         */
        public List<MethodMetric> violators() {
            return methods.stream()
                    .filter(m -> m.cyclomaticComplexity() > threshold)
                    .sorted(Comparator.comparingInt(MethodMetric::cyclomaticComplexity).reversed())
                    .toList();
        }

        /**
         * Returns true if all methods are at or below the CC threshold.
         */
        public boolean thresholdMet() {
            return methodsAboveThreshold == 0;
        }

        /**
         * Formats a markdown before/after comparison table.
         * Pass {@code null} for {@code before} to render a single-column report.
         */
        public String formatMarkdownComparison(CodeMetricsSnapshot before) {
            if (before == null) {
                return """
                        ## Cyclomatic Complexity Report

                        | Metric | Value |
                        |--------|-------|
                        | Total methods | %d |
                        | Methods above threshold (CC > %d) | %d |
                        | Average CC | %.2f |
                        | Maximum CC | %d |
                        """.formatted(totalMethods, threshold, methodsAboveThreshold, avgComplexity, maxComplexity);
            }

            String deltaViolators = formatDelta(before.methodsAboveThreshold - methodsAboveThreshold, true);
            String deltaAvg = formatDelta(before.avgComplexity - avgComplexity, true);

            return """
                    ## Cyclomatic Complexity Report

                    | Metric | Before | After | Delta |
                    |--------|--------|-------|-------|
                    | Total methods | %d | %d | %s |
                    | Methods above threshold (CC > %d) | %d | %d | %s |
                    | Average CC | %.2f | %.2f | %s |
                    | Maximum CC | %d | %d | %s |
                    """.formatted(
                    before.totalMethods, totalMethods,
                    formatDelta(totalMethods - before.totalMethods, false),
                    threshold,
                    before.methodsAboveThreshold, methodsAboveThreshold, deltaViolators,
                    before.avgComplexity, avgComplexity, deltaAvg,
                    before.maxComplexity, maxComplexity,
                    formatDelta(before.maxComplexity - maxComplexity, true));
        }

        /**
         * Compact prompt-ready summary with the worst violators listed.
         */
        public String formatForPrompt(int maxMethods) {
            StringBuilder sb = new StringBuilder();
            sb.append("## Cyclomatic Complexity Baseline\n\n");
            sb.append("| Metric | Value |\n|--------|-------|\n");
            sb.append("| Total methods | %d |\n".formatted(totalMethods));
            sb.append("| Methods above CC threshold (%d) | %d |\n".formatted(threshold, methodsAboveThreshold));
            sb.append("| Average CC | %.2f |\n".formatted(avgComplexity));
            sb.append("| Maximum CC | %d |\n\n".formatted(maxComplexity));

            List<MethodMetric> topViolators = violators().stream().limit(maxMethods).toList();
            if (!topViolators.isEmpty()) {
                sb.append("### Methods Exceeding CC Threshold (worst first)\n\n");
                sb.append("| File | Class | Method | CC | Lines |\n");
                sb.append("|------|-------|--------|----|-------|\n");
                for (MethodMetric m : topViolators) {
                    sb.append("| `%s` | `%s` | `%s` | **%d** | %d–%d |\n".formatted(
                            m.filePath(), simpleClassName(m.className()), m.methodName(),
                            m.cyclomaticComplexity(), m.lineStart(), m.lineEnd()));
                }
            }
            return sb.toString();
        }

        private static String simpleClassName(String fqn) {
            int dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        }

        private static String formatDelta(double delta, boolean positiveIsGood) {
            if (delta > 0) return positiveIsGood ? "+%.2f (improved)".formatted(delta) : "+%.0f".formatted(delta);
            if (delta < 0) return positiveIsGood ? "%.2f (worse)".formatted(delta) : "%.0f".formatted(delta);
            return "0 (unchanged)";
        }

        private static String formatDelta(int delta, boolean positiveIsGood) {
            if (delta > 0) return positiveIsGood ? "+%d (improved)".formatted(delta) : "+%d".formatted(delta);
            if (delta < 0) return positiveIsGood ? "%d (worse)".formatted(delta) : "%d".formatted(delta);
            return "0 (unchanged)";
        }
    }
}
