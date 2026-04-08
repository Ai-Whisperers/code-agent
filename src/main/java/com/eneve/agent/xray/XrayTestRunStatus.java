package com.eneve.agent.xray;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Valid status values for an Xray test run.
 *
 * <p>Single source of truth used by both the tool schema definition
 * ({@code XrayToolSchemas}) and the MCP tool executor
 * ({@code XrayUpdateTestRunStatusTool}).
 */
public enum XrayTestRunStatus {
    TODO,
    EXECUTING,
    PASS,
    FAIL,
    ABORTED,
    BLOCKED;

    /** All status names as an unmodifiable {@link Set}, for O(1) membership checks. */
    public static final Set<String> NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** All status names as an ordered {@link List}, for use in JSON schema {@code enum} arrays. */
    public static final List<String> NAME_LIST = Arrays.stream(values())
            .map(Enum::name)
            .toList();
}
