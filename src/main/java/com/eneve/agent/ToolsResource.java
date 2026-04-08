package com.eneve.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.ToolUnion;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.tools.ToolRegistry;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Read-only introspection endpoint that returns the tool schemas sent to Claude
 * for each supported mode. Useful for debugging "why didn't the agent call X?"
 * and for verifying that all expected tools are wired correctly.
 *
 * <p>Restricted to {@code app_admin} role.
 */
@Path("/tools")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Tool Introspection", description = "Inspect the Claude tool schemas available in each agent mode")
public class ToolsResource {

    @Inject
    ToolRegistry toolRegistry;

    @GET
    @Operation(
            operationId = "listToolSchemas",
            summary = "List tool schemas for a given agent mode",
            description = "Returns the names of all Claude tool schemas available in the specified mode, "
                    + "along with which of those tools have a registered ToolExecutor bean. "
                    + "Use mode=all for the fix loop, mode=chat for interactive chat, etc."
    )
    @APIResponse(responseCode = "200", description = "Tool schema list")
    @APIResponse(responseCode = "400", description = "Unknown mode")
    public Response list(
            @Parameter(description = "Agent mode: all, readOnly, chat, chatAdmin, planExecution, docsGeneration",
                    example = "chat")
            @QueryParam("mode") @DefaultValue("chat") String mode) {

        List<ToolUnion> tools = switch (mode.toLowerCase()) {
            case "all"            -> ToolDefinitions.all();
            case "readonly"       -> ToolDefinitions.readOnly();
            case "chat"           -> ToolDefinitions.chat();
            case "chatadmin"      -> ToolDefinitions.chat(true, true);
            case "planexecution"  -> ToolDefinitions.planExecution();
            case "docsgeneration" -> ToolDefinitions.docsGeneration();
            default -> null;
        };

        if (tools == null) {
            return Response.status(400)
                    .entity(Map.of("error", "Unknown mode: " + mode
                            + ". Valid values: all, readOnly, chat, chatAdmin, planExecution, docsGeneration"))
                    .build();
        }

        List<String> registeredExecutors = toolRegistry.toolNames();

        List<Map<String, Object>> result = tools.stream()
                .map(tu -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    if (tu.isTool()) {
                        String name = tu.asTool().name();
                        entry.put("name", name);
                        tu.asTool().description().ifPresent(d -> entry.put("description", d));
                        entry.put("executorRegistered", registeredExecutors.contains(name));
                    } else {
                        entry.put("name", "unknown");
                        entry.put("executorRegistered", false);
                    }
                    return entry;
                })
                .toList();

        return Response.ok(Map.of(
                "mode", mode,
                "toolCount", result.size(),
                "tools", result
        )).build();
    }

    @GET
    @Path("/registered")
    @Operation(
            operationId = "listRegisteredExecutors",
            summary = "List all registered ToolExecutor beans",
            description = "Returns the names of all CDI ToolExecutor beans known to the ToolRegistry."
    )
    @APIResponse(responseCode = "200", description = "Registered executor names")
    public Response listRegistered() {
        List<String> names = toolRegistry.toolNames().stream().sorted().toList();
        return Response.ok(Map.of("count", names.size(), "executors", names)).build();
    }
}
