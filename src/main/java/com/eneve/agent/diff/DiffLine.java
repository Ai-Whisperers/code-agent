package com.eneve.agent.diff;

public record DiffLine(
    Type type,
    int newLineNo,
    String content
) {
    public enum Type { CONTEXT, ADDED, REMOVED }
}
