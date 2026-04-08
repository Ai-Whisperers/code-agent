package com.eneve.agent.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request to discover and generate a cloud architecture diagram for a customer environment.
 * The agent queries AWS ECS and RDS to enumerate running services and databases,
 * builds a Structurizr DSL model, and stores versioned diagrams.
 */
@Schema(description = "Cloud architecture discovery job request")
public record GenerateCloudArchitectureRequest(
        @Schema(description = "Customer identifier (slug) as configured in the customer registry", required = true)
        String customerId,

        @Schema(description = "Environment name to discover (e.g. 'production', 'acceptance')", required = true)
        String environmentName
) implements JobPayload {}
