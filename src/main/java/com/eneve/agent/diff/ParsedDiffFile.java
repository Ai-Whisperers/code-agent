package com.eneve.agent.diff;

import java.util.List;

public record ParsedDiffFile(
    String path,
    List<DiffHunk> hunks
) {}
