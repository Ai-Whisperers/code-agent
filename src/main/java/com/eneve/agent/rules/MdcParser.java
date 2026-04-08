package com.eneve.agent.rules;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Parses .mdc files with YAML frontmatter (between --- markers) and a body.
 * Also handles plain .cursorrules and AGENTS.md files (no frontmatter).
 */
public final class MdcParser {

    private static final String FRONTMATTER_DELIMITER = "---";

    private MdcParser() { }

    public static MdcRule parse(String fileName, String content) {
        if (content == null || content.isBlank()) {
            return new MdcRule(fileName, "", Collections.emptyList(), false, "");
        }

        String trimmed = content.strip();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return new MdcRule(fileName, "", Collections.emptyList(), true, trimmed);
        }

        int secondDelimiter = trimmed.indexOf(FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (secondDelimiter < 0) {
            return new MdcRule(fileName, "", Collections.emptyList(), true, trimmed);
        }

        String yamlBlock = trimmed.substring(FRONTMATTER_DELIMITER.length(), secondDelimiter).strip();
        String body = trimmed.substring(secondDelimiter + FRONTMATTER_DELIMITER.length()).strip();

        String description = "";
        List<String> globs = Collections.emptyList();
        boolean alwaysApply = false;

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlBlock);
            if (frontmatter != null) {
                description = stringVal(frontmatter, "description");
                alwaysApply = boolVal(frontmatter, "alwaysApply");
                globs = listVal(frontmatter, "globs");
            }
        } catch (Exception ignored) {
            // If frontmatter is malformed, treat entire content as body
        }

        return new MdcRule(fileName, description, globs, alwaysApply, body);
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private static boolean boolVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (val instanceof String s && !s.isBlank()) {
            return List.of(s.split(",")).stream().map(String::strip).toList();
        }
        return Collections.emptyList();
    }
}
