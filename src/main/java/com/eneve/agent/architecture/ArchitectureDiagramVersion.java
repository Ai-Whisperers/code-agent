package com.eneve.agent.architecture;

import java.time.Instant;

/**
 * Represents one immutable version row from {@code architecture_diagram_versions}.
 *
 * @param id          primary key
 * @param repoSlug    set for code-architecture diagrams; null for cloud diagrams
 * @param customerId  set for cloud diagrams; null for code-architecture diagrams
 * @param environment set for cloud diagrams; null for code-architecture diagrams
 * @param viewName    the Structurizr view key (e.g. "SystemContext", "Containers")
 * @param viewType    C4 view type (e.g. "SystemContext", "Container", "Component", "Cloud")
 * @param version     monotonically increasing version number per (scope, viewName)
 * @param source      "ai" or "human"
 * @param pinned      true if this version is the AI baseline for the next generation
 * @param dslSrc      full Structurizr workspace DSL (scope-level, shared across views of same generation)
 * @param mermaidSrc  rendered Mermaid source for this specific view
 * @param createdAt   when this version was created
 */
public record ArchitectureDiagramVersion(
        long id,
        String repoSlug,
        String customerId,
        String environment,
        String viewName,
        String viewType,
        int version,
        String source,
        boolean pinned,
        String dslSrc,
        String mermaidSrc,
        Instant createdAt
) {}
