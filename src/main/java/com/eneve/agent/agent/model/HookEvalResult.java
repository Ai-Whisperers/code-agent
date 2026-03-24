package com.eneve.agent.agent.model;

import java.util.List;

public record HookEvalResult(List<String> jobIds, List<String> hookNames) {

    public static HookEvalResult empty() {
        return new HookEvalResult(List.of(), List.of());
    }

    public boolean isEmpty() {
        return jobIds.isEmpty();
    }

    public int size() {
        return jobIds.size();
    }
}
