package com.eneve.agent.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects Node.js package manager, install commands, and test commands for TypeScript/JavaScript repos.
 * Mirrors the behaviour of {@link com.eneve.agent.agent.JsCoverageReporter} for lockfile precedence.
 */
public final class NodeProjectHelper {

    private NodeProjectHelper() {}

    public enum PackageManager {
        NPM,
        PNPM,
        YARN
    }

    /**
     * Chooses npm, pnpm, or yarn from lockfiles at the project root.
     */
    public static PackageManager detectPackageManager(Path root) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) {
            return PackageManager.PNPM;
        }
        if (Files.exists(root.resolve("yarn.lock"))) {
            return PackageManager.YARN;
        }
        return PackageManager.NPM;
    }

    /**
     * Primary test invocation for the detected package manager (e.g. {@code pnpm test}).
     */
    public static String testCommand(PackageManager pm) {
        return switch (pm) {
            case PNPM -> "pnpm test";
            case YARN -> "yarn test";
            case NPM -> "npm test";
        };
    }

    /**
     * Whether {@code package.json} defines a non-placeholder {@code test} script.
     */
    public static boolean hasRunnableTestScript(Path root) {
        if (!Files.exists(root.resolve("package.json"))) {
            return false;
        }
        try {
            String content = Files.readString(root.resolve("package.json"));
            if (!content.contains("\"test\"")) {
                return false;
            }
            return !content.contains("\"test\": \"echo \\\"Error: no test specified\\\"");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether an Angular CLI workspace exists at the repo root.
     */
    public static boolean isAngularWorkspace(Path root) {
        return Files.exists(root.resolve("angular.json"));
    }

    /**
     * Suggested single-line test command for agent prompts and guardrails.
     * Prefers package-manager-specific {@code test} scripts; for Angular workspaces without a
     * {@code test} script, suggests {@code npx ng test}.
     */
    public static String suggestedTestCommand(Path root) {
        PackageManager pm = detectPackageManager(root);
        if (isAngularWorkspace(root) && !hasRunnableTestScript(root)) {
            return "npx ng test --no-watch --browsers=ChromeHeadless --no-progress";
        }
        if (hasRunnableTestScript(root)) {
            return testCommand(pm);
        }
        if (Files.exists(root.resolve("vitest.config.ts"))
                || Files.exists(root.resolve("vitest.config.mts"))
                || Files.exists(root.resolve("vitest.config.cts"))
                || Files.exists(root.resolve("vitest.config.js"))
                || Files.exists(root.resolve("vitest.config.mjs"))
                || Files.exists(root.resolve("vitest.config.cjs"))) {
            return switch (pm) {
                case PNPM -> "pnpm exec vitest run";
                case YARN -> "yarn exec vitest run";
                case NPM -> "npx vitest run";
            };
        }
        if (hasJestDependency(root)) {
            return switch (pm) {
                case PNPM -> "pnpm exec jest";
                case YARN -> "yarn exec jest";
                case NPM -> "npx jest";
            };
        }
        return testCommand(pm);
    }

    private static boolean hasJestDependency(Path root) {
        try {
            String pkg = Files.readString(root.resolve("package.json"));
            return pkg.contains("\"jest\"");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Full shell command: install dependencies (respecting lockfile) then run tests.
     * Used by {@link com.eneve.agent.agent.BuildValidator} for Node projects.
     */
    public static String installAndTestCommand(Path root) {
        PackageManager pm = detectPackageManager(root);
        String install = installCommand(pm, root);
        String test;
        if (isAngularWorkspace(root) && !hasRunnableTestScript(root)) {
            test = "npx ng test --no-watch --code-coverage --browsers=ChromeHeadless --no-progress";
        } else if (hasRunnableTestScript(root)) {
            test = testCommand(pm);
        } else if (Files.exists(root.resolve("vitest.config.ts"))
                || Files.exists(root.resolve("vitest.config.mts"))
                || Files.exists(root.resolve("vitest.config.cts"))
                || Files.exists(root.resolve("vitest.config.js"))
                || Files.exists(root.resolve("vitest.config.mjs"))
                || Files.exists(root.resolve("vitest.config.cjs"))) {
            test = switch (pm) {
                case PNPM -> "pnpm exec vitest run";
                case YARN -> "yarn exec vitest run";
                case NPM -> "npx vitest run";
            };
        } else if (hasJestDependency(root)) {
            test = switch (pm) {
                case PNPM -> "pnpm exec jest --passWithNoTests";
                case YARN -> "yarn exec jest --passWithNoTests";
                case NPM -> "npx jest --passWithNoTests";
            };
        } else {
            test = testCommand(pm);
        }
        return install + " && " + test;
    }

    /**
     * Install command for the given package manager, with npm {@code ci} when {@code package-lock.json} exists.
     */
    public static String installCommand(PackageManager pm, Path root) {
        return switch (pm) {
            case PNPM -> "pnpm install --frozen-lockfile --ignore-scripts";
            case YARN -> "yarn install --frozen-lockfile --ignore-scripts";
            case NPM -> {
                boolean hasLock = Files.exists(root.resolve("package-lock.json"));
                if (hasLock) {
                    yield "npm ci --ignore-scripts || npm install --ignore-scripts";
                }
                yield "npm install --ignore-scripts";
            }
        };
    }

    /**
     * Install command for linters: same as {@link #installCommand(PackageManager, Path)} for pnpm/yarn;
     * for npm uses a single attempt pattern suitable for {@link com.eneve.agent.linter.EsLintRunner}.
     */
    public static String linterInstallCommand(PackageManager pm, Path root) {
        if (pm == PackageManager.NPM && Files.exists(root.resolve("package-lock.json"))) {
            return "npm ci --ignore-scripts || npm install --ignore-scripts";
        }
        return installCommand(pm, root);
    }

}
