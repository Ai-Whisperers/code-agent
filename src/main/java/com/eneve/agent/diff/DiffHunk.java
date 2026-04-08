package com.eneve.agent.diff;

import java.util.List;

public record DiffHunk(
    int newStart,
    int newCount,
    List<DiffLine> lines
) {}
