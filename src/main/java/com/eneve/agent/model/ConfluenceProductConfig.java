package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Confluence space configuration for a product")
public record ConfluenceProductConfig(
        @Schema(description = "Confluence space key", example = "MYPRODUCT")
        String spaceKey,

        @Schema(description = "Root page ID for this product's documentation", example = "123456")
        String rootPageId
) {}
