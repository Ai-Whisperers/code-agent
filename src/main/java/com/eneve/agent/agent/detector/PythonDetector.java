package com.eneve.agent.agent.detector;

import com.eneve.agent.agent.ArchetypeDetector.ArchetypeInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Python projects and their archetype (FastAPI, Django, Flask, or generic Python).
 *
 * <p>Precedence by framework dependency:
 * <ol>
 *   <li>FastAPI ({@code fastapi})</li>
 *   <li>Django ({@code Django} or {@code django})</li>
 *   <li>Flask ({@code flask})</li>
 *   <li>Generic Python</li>
 * </ol>
 *
 * <p>Sources checked, in order:
 * <ol>
 *   <li>{@code pyproject.toml} {@code [project]} table — the modern canonical manifest</li>
 *   <li>{@code requirements.txt} — pip classic</li>
 *   <li>{@code setup.py} / {@code setup.cfg} — legacy fallback</li>
 * </ol>
 */
public class PythonDetector implements Detector {

    private static final Logger LOG = Logger.getLogger(PythonDetector.class);

    // Match lines like:  "fastapi>=0.104,<1.0"   or   fastapi = "^0.104"
    private static final Pattern DEP_LINE = Pattern.compile(
        "[\"']?([A-Za-z0-9_.\\-]+)[\"']?\\s*[=<>!~]?=?\\s*[\"']?([A-Za-z0-9.\\-]*)"
    );

    @Override
    public ArchetypeInfo detect(Path projectRoot) {
        Path pyproject = projectRoot.resolve("pyproject.toml");
        Path requirements = projectRoot.resolve("requirements.txt");
        Path setupPy = projectRoot.resolve("setup.py");

        if (!Files.exists(pyproject) && !Files.exists(requirements) && !Files.exists(setupPy)) {
            return null;
        }

        String content;
        if (Files.exists(pyproject)) {
            content = read(pyproject);
        } else if (Files.exists(requirements)) {
            content = read(requirements);
        } else {
            content = read(setupPy);
        }
        if (content == null) {
            return null;
        }

        String pyVersion = extractPythonVersion(content);

        // Framework detection by substring match in the concatenated manifest content
        String lowered = content.toLowerCase();
        if (lowered.contains("fastapi")) {
            LOG.debugf("Detected FastAPI project (python %s)", pyVersion);
            return new ArchetypeInfo("python-fastapi", pyVersion);
        }
        if (lowered.contains("\"django\"") || lowered.contains("'django'")
            || lowered.matches("(?s).*^django[=<>~!].*") || lowered.contains("\ndjango ")) {
            LOG.debugf("Detected Django project (python %s)", pyVersion);
            return new ArchetypeInfo("python-django", pyVersion);
        }
        if (lowered.contains("\"flask\"") || lowered.contains("'flask'")
            || lowered.contains("\nflask")) {
            LOG.debugf("Detected Flask project (python %s)", pyVersion);
            return new ArchetypeInfo("python-flask", pyVersion);
        }
        LOG.debugf("Detected generic Python project (python %s)", pyVersion);
        return new ArchetypeInfo("python", pyVersion);
    }

    /**
     * Extracts the requires-python value from pyproject.toml, or the python_requires
     * value from setup.py / setup.cfg. Returns the raw string (e.g. "3.10" or ">=3.10,<3.13").
     */
    static String extractPythonVersion(String content) {
        // pyproject.toml:  requires-python = ">=3.10,<3.13"
        Pattern requiresPython = Pattern.compile(
            "requires-python\\s*=\\s*[\"']([^\"']+)[\"']"
        );
        Matcher m = requiresPython.matcher(content);
        if (m.find()) {
            return cleanVersionString(m.group(1));
        }
        // setup.py:  python_requires=">=3.10"
        Pattern pythonRequires = Pattern.compile(
            "python_requires\\s*=\\s*[\"']([^\"']+)[\"']"
        );
        m = pythonRequires.matcher(content);
        if (m.find()) {
            return cleanVersionString(m.group(1));
        }
        return "unknown";
    }

    /**
     * Strips version range operators to leave a bare version number where possible.
     * {@code ">=3.10"} -> {@code "3.10"}.
     */
    static String cleanVersionString(String version) {
        if (version == null) return "unknown";
        String v = version.trim().replaceAll("^[~^>=<!]+", "").trim();
        // If there's a comma, take the first token
        int comma = v.indexOf(',');
        if (comma > 0) {
            v = v.substring(0, comma).trim();
            v = v.replaceAll("^[~^>=<!]+", "").trim();
        }
        return v.isEmpty() ? version.trim() : v;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            LOG.warnf("PythonDetector: failed to read %s: %s", p, e.getMessage());
            return null;
        }
    }
}
