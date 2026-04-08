package com.eneve.agent.diff;

import java.util.Map;
import java.util.TreeSet;

/**
 * Bundles the review system prompt with the commentable-lines index
 * built during diff parsing, so validation can happen at comment-posting time.
 */
public record ReviewPromptResult(
    String prompt,
    Map<String, TreeSet<Integer>> commentableLines,
    boolean diffTruncated
) {}
