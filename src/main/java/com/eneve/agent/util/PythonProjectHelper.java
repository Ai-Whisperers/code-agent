package com.eneve.agent.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects Python package manager, install commands, and test commands.
 *
 * <p>Mirrors the shape of {@link NodeProjectHelper} but for the Python
 * ecosystem. Recognises {@code uv}, {@code poetry}, and plain {@code pip}
 * projects, chosen by lockfile / manifest precedence:
 *
 * <ol>
 *   <li>{@code uv.lock}   → uv     (fastest, preferred for AIW Solstein)</li>
 *   <li>{@code poetry.lock} → poetry</li>
 *   <li>{@code requirements.txt} or {@code setup.py} → pip</li>
 *   <li>{@code pyproject.toml} without a lockfile → pip (PEP 517)</li>
 * </ol>
 *
 * <p>For test detection, checks for {@code pytest.ini}, {@code pyproject.toml}
 * with {@code [tool.pytest]}, {@code tests/} directory with {@code test_*.py}
 * files, or {@code conftest.py}.
 */
public final class PythonProjectHelper {

    private PythonProjectHelper() {}

    public enum PackageManager {
        UV,
        POETRY,
        PIP
    }

    /**
     * Chooses uv, poetry, or pip from lockfiles / manifests at the project root.
     */
    public static PackageManager detectPackageManager(Path root) {
        if (Files.exists(root.resolve("uv.lock"))) {
            return PackageManager.UV;
        }
        if (Files.exists(root.resolve("poetry.lock"))) {
            return PackageManager.POETRY;
        }
        return PackageManager.PIP;
    }

    /**
     * Returns true if the directory looks like a Python project:
     * has a pyproject.toml, requirements.txt, setup.py, or Pipfile.
     */
    public static boolean isPythonProject(Path root) {
        return Files.exists(root.resolve("pyproject.toml"))
            || Files.exists(root.resolve("requirements.txt"))
            || Files.exists(root.resolve("setup.py"))
            || Files.exists(root.resolve("Pipfile"));
    }

    /**
     * Install command for the detected package manager.
     */
    public static String installCommand(Path root) {
        return switch (detectPackageManager(root)) {
            case UV     -> "uv sync --all-extras";
            case POETRY -> "poetry install --with dev";
            case PIP    -> detectPipInstall(root);
        };
    }

    private static String detectPipInstall(Path root) {
        if (Files.exists(root.resolve("pyproject.toml"))) {
            return "python -m pip install -e '.[dev]' 2>/dev/null || python -m pip install -e .";
        }
        if (Files.exists(root.resolve("requirements.txt"))) {
            return "python -m pip install -r requirements.txt";
        }
        return "python -m pip install -e .";
    }

    /**
     * True when the project has a runnable pytest configuration.
     * Covers: pytest.ini, pyproject.toml [tool.pytest.ini_options], setup.cfg
     * [tool:pytest], tox.ini [pytest], or a tests/ dir with test_*.py files.
     */
    public static boolean hasPytestConfig(Path root) {
        if (Files.exists(root.resolve("pytest.ini"))) {
            return true;
        }
        if (Files.exists(root.resolve("conftest.py"))) {
            return true;
        }
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.exists(pyproject)) {
            try {
                String content = Files.readString(pyproject);
                if (content.contains("[tool.pytest")) {
                    return true;
                }
            } catch (IOException ignored) {}
        }
        Path setupCfg = root.resolve("setup.cfg");
        if (Files.exists(setupCfg)) {
            try {
                String content = Files.readString(setupCfg);
                if (content.contains("[tool:pytest]") || content.contains("[pytest]")) {
                    return true;
                }
            } catch (IOException ignored) {}
        }
        // Tests directory with at least one test file
        Path testsDir = root.resolve("tests");
        if (Files.isDirectory(testsDir)) {
            try (var stream = Files.walk(testsDir, 2)) {
                return stream.anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("test_") && name.endsWith(".py");
                });
            } catch (IOException ignored) {}
        }
        return false;
    }

    /**
     * Suggested single-line test command. Uses {@code uv run} / {@code poetry run}
     * wrapping to pick up the project's virtualenv. Sets {@code PYTHONPATH=src}
     * when a {@code src/} layout is detected (the AIW Solstein convention).
     */
    public static String suggestedTestCommand(Path root) {
        PackageManager pm = detectPackageManager(root);
        String pytestArgs = "-q --maxfail=1";
        String pytestCmd = switch (pm) {
            case UV     -> "uv run pytest " + pytestArgs;
            case POETRY -> "poetry run pytest " + pytestArgs;
            case PIP    -> "python -m pytest " + pytestArgs;
        };
        if (Files.isDirectory(root.resolve("src"))) {
            return "PYTHONPATH=src " + pytestCmd;
        }
        return pytestCmd;
    }

    /**
     * Combined install + test command for BuildValidator.
     */
    public static String installAndTestCommand(Path root) {
        if (!hasPytestConfig(root)) {
            return null;
        }
        return installCommand(root) + " && " + suggestedTestCommand(root);
    }

    /**
     * Suggested ruff lint command, if ruff is configured.
     * Checks for {@code [tool.ruff]} in pyproject.toml or a standalone {@code ruff.toml}.
     */
    public static String suggestedLintCommand(Path root) {
        if (!hasRuffConfig(root)) {
            return null;
        }
        PackageManager pm = detectPackageManager(root);
        String target = Files.isDirectory(root.resolve("src")) ? "src/" : ".";
        return switch (pm) {
            case UV     -> "uv run ruff check " + target;
            case POETRY -> "poetry run ruff check " + target;
            case PIP    -> "python -m ruff check " + target;
        };
    }

    private static boolean hasRuffConfig(Path root) {
        if (Files.exists(root.resolve("ruff.toml")) || Files.exists(root.resolve(".ruff.toml"))) {
            return true;
        }
        Path pyproject = root.resolve("pyproject.toml");
        if (Files.exists(pyproject)) {
            try {
                return Files.readString(pyproject).contains("[tool.ruff");
            } catch (IOException ignored) {}
        }
        return false;
    }
}
