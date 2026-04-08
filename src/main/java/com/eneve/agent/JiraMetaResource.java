package com.eneve.agent;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.jira.JiraService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * Lightweight proxy endpoints for Jira project and component metadata.
 * Used by the UI to populate the Jira component picker in RepoSettings.
 */
@Path("/jira/meta")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("app_admin")
@Tag(name = "Jira Meta", description = "Jira project and component lookup for repo settings")
public class JiraMetaResource {

    private static final Logger LOG = Logger.getLogger(JiraMetaResource.class);

    @Inject JiraService jiraService;
    @Inject CustomerRegistryStore registryStore;
    @Inject ObjectMapper mapper;

    @GET
    @Path("/projects")
    @Operation(
            operationId = "listJiraProjects",
            summary = "List all Jira projects",
            description = "Returns id, key and name for every project visible to the configured Jira service account."
    )
    @APIResponse(responseCode = "200", description = "Array of {id, key, name}")
    @APIResponse(responseCode = "503", description = "Jira not configured or unreachable")
    public Response listProjects() {
        if (!jiraService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Jira is not configured\"}").build();
        }
        try {
            ArrayNode result = mapper.createArrayNode();
            int startAt = 0;
            final int maxResults = 500;
            boolean hasMore = true;

            while (hasMore) {
                String raw = jiraService.listProjectsPageRaw(startAt, maxResults);
                if (raw == null) {
                    if (startAt == 0) {
                        return Response.status(503).entity("{\"error\":\"Jira request failed\"}").build();
                    }
                    break;
                }
                JsonNode root = mapper.readTree(raw);

                // Jira returns either a plain array or a paginated object {values:[...], isLast:bool}
                if (root.isArray()) {
                    for (JsonNode p : root) {
                        result.add(projectNode(p));
                    }
                    hasMore = false; // plain array = all results in one shot
                } else {
                    JsonNode values = root.path("values");
                    if (values.isArray()) {
                        for (JsonNode p : values) {
                            result.add(projectNode(p));
                        }
                        boolean isLast = root.path("isLast").asBoolean(true);
                        int total = root.path("total").asInt(-1);
                        hasMore = !isLast && (total < 0 || startAt + maxResults < total);
                        startAt += maxResults;
                    } else {
                        hasMore = false;
                    }
                }
            }

            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warnf("Failed to parse Jira projects response: %s", e.getMessage());
            return Response.status(503).entity("{\"error\":\"Failed to parse Jira response\"}").build();
        }
    }

    private ObjectNode projectNode(JsonNode p) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id",   p.path("id").asText(""));
        item.put("key",  p.path("key").asText(""));
        item.put("name", p.path("name").asText(""));
        return item;
    }

    @GET
    @Path("/projects/{projectKey}/components")
    @Operation(
            operationId = "listJiraComponents",
            summary = "List components for a Jira project",
            description = "Returns id and name for every component in the specified Jira project."
    )
    @APIResponse(responseCode = "200", description = "Array of {id, name}")
    @APIResponse(responseCode = "503", description = "Jira not configured or unreachable")
    public Response listComponents(
            @Parameter(description = "Jira project key", required = true)
            @PathParam("projectKey") String projectKey) {
        if (!jiraService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Jira is not configured\"}").build();
        }
        String raw = jiraService.listComponentsRaw(projectKey);
        if (raw == null) {
            return Response.status(503).entity("{\"error\":\"Jira request failed\"}").build();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            ArrayNode result = mapper.createArrayNode();
            if (root.isArray()) {
                for (JsonNode c : root) {
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id",   c.path("id").asText(""));
                    item.put("name", c.path("name").asText(""));
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warnf("Failed to parse Jira components response for %s: %s", projectKey, e.getMessage());
            return Response.status(503).entity("{\"error\":\"Failed to parse Jira response\"}").build();
        }
    }

    @GET
    @Path("/priorities")
    @RolesAllowed({"app_admin", "app_developer"})
    @Operation(
            operationId = "listJiraPriorities",
            summary = "List all Jira priorities",
            description = "Returns id and name for every priority configured in Jira."
    )
    @APIResponse(responseCode = "200", description = "Array of {id, name}")
    @APIResponse(responseCode = "503", description = "Jira not configured or unreachable")
    public Response listPriorities() {
        if (!jiraService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Jira is not configured\"}").build();
        }
        String raw = jiraService.listPrioritiesRaw();
        if (raw == null) {
            return Response.status(503).entity("{\"error\":\"Jira request failed\"}").build();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            ArrayNode result = mapper.createArrayNode();
            if (root.isArray()) {
                for (JsonNode p : root) {
                    ObjectNode item = mapper.createObjectNode();
                    item.put("id",   p.path("id").asText(""));
                    item.put("name", p.path("name").asText(""));
                    result.add(item);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warnf("Failed to parse Jira priorities response: %s", e.getMessage());
            return Response.status(503).entity("{\"error\":\"Failed to parse Jira response\"}").build();
        }
    }

    @GET
    @Path("/repo-product")
    @Operation(
            operationId = "getRepoProduct",
            summary = "Get the Jira config for the product that owns a repo",
            description = "Looks up the product linked to the given workspace/repoSlug and returns its JiraProjectConfig. "
                    + "Used by the UI to pre-populate the Jira project picker with already-configured project keys."
    )
    @APIResponse(responseCode = "200", description = "Jira project config {projects: {role: key}}")
    @APIResponse(responseCode = "404", description = "No product found for this repo")
    public Response getRepoProduct(
            @Parameter(description = "Git workspace slug", required = true)
            @QueryParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @QueryParam("repoSlug") String repoSlug) {

        if (workspace == null || workspace.isBlank() || repoSlug == null || repoSlug.isBlank()) {
            return Response.status(400).entity("{\"error\":\"workspace and repoSlug are required\"}").build();
        }

        return registryStore.findByRepoSlug(workspace, repoSlug)
                .map(product -> {
                    ObjectNode body = mapper.createObjectNode();
                    if (product.jira() != null && product.jira().projects() != null) {
                        ObjectNode projects = mapper.createObjectNode();
                        product.jira().projects().forEach(projects::put);
                        body.set("projects", projects);
                    } else {
                        body.set("projects", mapper.createObjectNode());
                    }
                    return Response.ok(body).build();
                })
                .orElse(Response.status(404).entity("{\"error\":\"No product found for this repo\"}").build());
    }
}
