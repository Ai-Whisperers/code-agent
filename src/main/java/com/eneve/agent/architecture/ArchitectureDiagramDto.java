package com.eneve.agent.architecture;

/**
 * A single rendered view extracted from a Structurizr workspace.
 *
 * @param viewName   the key of the view in the DSL (e.g. "Diagram1", "SystemContext")
 * @param viewType   the C4 view type (e.g. "SystemContext", "Container", "Component", "Dynamic")
 * @param mermaidSrc the Mermaid diagram source exported from this view
 */
public record ArchitectureDiagramDto(
        String viewName,
        String viewType,
        String mermaidSrc
) {}
