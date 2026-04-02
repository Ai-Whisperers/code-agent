package com.eneve.agent.agent;

import com.eneve.agent.agent.CoverageReporter.CoverageSnapshot;
import com.eneve.agent.agent.CoverageReporter.PackageCoverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an LCOV {@code lcov.info} file into a {@link CoverageSnapshot}.
 *
 * <p>LCOV is the default coverage format produced by Angular/Karma (via Istanbul).
 * The format is line-oriented:
 * <pre>
 *   SF:&lt;source file&gt;
 *   DA:&lt;line&gt;,&lt;hit count&gt;
 *   BRDA:&lt;line&gt;,&lt;block&gt;,&lt;branch&gt;,&lt;taken&gt;
 *   FN:&lt;line&gt;,&lt;name&gt;
 *   FNDA:&lt;hit count&gt;,&lt;name&gt;
 *   end_of_record
 * </pre>
 *
 * <p>Only line and branch counters are extracted; method and class counts are
 * approximated from the function (FN/FNDA) records.
 */
public final class LcovParser {

    private LcovParser() {}

    public static CoverageSnapshot parse(Path lcovFile) throws IOException {
        List<String> lines = Files.readAllLines(lcovFile);

        int linesCovered = 0, linesMissed = 0;
        int branchesCovered = 0, branchesMissed = 0;
        int methodsCovered = 0, methodsMissed = 0;

        // Per-file line counters for package-level summary
        // Key = directory path (package approximation)
        Map<String, int[]> packageLines = new LinkedHashMap<>(); // [covered, missed]

        String currentFile = null;
        int fileCovered = 0, fileMissed = 0;

        for (String line : lines) {
            if (line.startsWith("SF:")) {
                currentFile = line.substring(3).trim();
                fileCovered = 0;
                fileMissed = 0;
            } else if (line.startsWith("DA:")) {
                // DA:<line number>,<execution count>
                String[] parts = line.substring(3).split(",", 2);
                if (parts.length == 2) {
                    try {
                        int hits = Integer.parseInt(parts[1].trim());
                        if (hits > 0) { linesCovered++; fileCovered++; }
                        else          { linesMissed++;  fileMissed++;  }
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.startsWith("BRDA:")) {
                // BRDA:<line>,<block>,<branch>,<taken>  — taken is "-" when not reachable
                String[] parts = line.substring(5).split(",", 4);
                if (parts.length == 4) {
                    String taken = parts[3].trim();
                    if ("-".equals(taken)) {
                        // unreachable branch — skip
                    } else {
                        try {
                            int hits = Integer.parseInt(taken);
                            if (hits > 0) branchesCovered++;
                            else          branchesMissed++;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (line.startsWith("FNDA:")) {
                // FNDA:<hit count>,<function name>
                String[] parts = line.substring(5).split(",", 2);
                if (parts.length == 2) {
                    try {
                        int hits = Integer.parseInt(parts[0].trim());
                        if (hits > 0) methodsCovered++;
                        else          methodsMissed++;
                    } catch (NumberFormatException ignored) {}
                }
            } else if ("end_of_record".equals(line.trim()) && currentFile != null) {
                // Accumulate into package bucket (directory of the source file)
                String pkg = packageOf(currentFile);
                packageLines.computeIfAbsent(pkg, k -> new int[2]);
                packageLines.get(pkg)[0] += fileCovered;
                packageLines.get(pkg)[1] += fileMissed;
                currentFile = null;
            }
        }

        // Classes ≈ number of source files (not tracked by LCOV directly)
        int classesCovered = (int) packageLines.values().stream()
                .filter(v -> v[0] > 0).count();
        int classesMissed = (int) packageLines.values().stream()
                .filter(v -> v[0] == 0 && v[1] > 0).count();

        List<PackageCoverage> packages = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : packageLines.entrySet()) {
            int[] v = entry.getValue();
            if (v[0] + v[1] > 0) {
                packages.add(new PackageCoverage(entry.getKey(), v[0], v[1]));
            }
        }

        return new CoverageSnapshot(
                linesCovered, linesMissed,
                branchesCovered, branchesMissed,
                methodsCovered, methodsMissed,
                classesCovered, classesMissed,
                packages);
    }

    /** Returns the parent directory of a source file path as a package approximation. */
    private static String packageOf(String filePath) {
        int slash = filePath.lastIndexOf('/');
        if (slash < 0) slash = filePath.lastIndexOf('\\');
        return slash > 0 ? filePath.substring(0, slash) : filePath;
    }
}
