package com.eneve.agent.rules;

import java.util.List;

/**
 * Parsed representation of a .mdc Cursor rule file.
 */
public record MdcRule(
        String fileName,
        String description,
        List<String> globs,
        boolean alwaysApply,
        String body
) {
}
