package com.eneve.agent.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Registry that collects all ToolExecutor beans and provides lookup by name.
 */
@ApplicationScoped
public class ToolRegistry {

    @Inject
    Instance<ToolExecutor> toolInstances;

    private final Map<String, ToolExecutor> tools = new HashMap<>();

    @PostConstruct
    void init() {
        for (ToolExecutor tool : toolInstances) {
            tools.put(tool.name(), tool);
        }
    }

    public ToolExecutor get(String name) {
        return tools.get(name);
    }

    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }
}
