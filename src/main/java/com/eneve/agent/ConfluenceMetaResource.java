package com.eneve.agent;

import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.security.SsrfGuard;
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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight proxy endpoints for Confluence space and page metadata.
 * Used by the UI to populate the Confluence page picker in product settings.
 */
@Path("/confluence/meta")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("app_admin")
@Tag(name = "Confluence Meta", description = "Confluence space and page lookup for product settings")
public class ConfluenceMetaResource {

    private static final Logger LOG = Logger.getLogger(ConfluenceMetaResource.class);

    @Inject ConfluenceService confluenceService;
    @Inject ObjectMapper mapper;
    @Inject HttpClient httpClient;

    @GET
    @Path("/spaces")
    @Operation(
            operationId = "listConfluenceSpaces",
            summary = "List all Confluence spaces",
            description = "Returns key and name for every space visible to the configured Confluence service account."
    )
    @APIResponse(responseCode = "200", description = "Array of {key, name}")
    @APIResponse(responseCode = "503", description = "Confluence not configured or unreachable")
    public Response listSpaces() {
        if (!confluenceService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Confluence is not configured\"}").build();
        }
        try {
            String baseUrl = confluenceService.getBaseUrl();
            ArrayNode result = mapper.createArrayNode();
            String nextUrl = baseUrl + "/wiki/api/v2/spaces?limit=250&type=global";

            while (nextUrl != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(SsrfGuard.safeSameHostUri(baseUrl, nextUrl))
                        .header("Authorization", basicAuthHeader())
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.warnf("Confluence listSpaces failed (%d): %s", response.statusCode(), response.body());
                    return Response.status(503).entity("{\"error\":\"Confluence request failed\"}").build();
                }

                var root = mapper.readTree(response.body());
                for (var space : root.path("results")) {
                    ObjectNode item = mapper.createObjectNode();
                    item.put("key", space.path("key").asText(""));
                    item.put("name", space.path("name").asText(""));
                    result.add(item);
                }

                // Follow the next-page cursor if present
                String cursor = root.path("_links").path("next").asText(null);
                nextUrl = (cursor != null && !cursor.isBlank()) ? baseUrl + cursor : null;
            }

            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warnf("Failed to list Confluence spaces: %s", e.getMessage());
            return Response.status(503).entity("{\"error\":\"Failed to list Confluence spaces\"}").build();
        }
    }

    @GET
    @Path("/spaces/{spaceKey}/pages")
    @Operation(
            operationId = "listConfluencePages",
            summary = "List pages in a Confluence space",
            description = "Returns pageId and title for pages in the given space. Optionally filter by title query."
    )
    @APIResponse(responseCode = "200", description = "Array of {pageId, title}")
    @APIResponse(responseCode = "503", description = "Confluence not configured or unreachable")
    public Response listPages(
            @Parameter(description = "Confluence space key", required = true)
            @PathParam("spaceKey") String spaceKey,
            @Parameter(description = "Optional title search query")
            @QueryParam("q") String query) {

        if (!confluenceService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Confluence is not configured\"}").build();
        }
        try {
            String cql;
            if (query != null && !query.isBlank()) {
                cql = "space=\"" + spaceKey + "\" AND title~\"" + query.replace("\"", "") + "\" AND type=page ORDER BY title ASC";
            } else {
                cql = "space=\"" + spaceKey + "\" AND type=page ORDER BY title ASC";
            }
            String baseUrl = confluenceService.getBaseUrl();
            String url = baseUrl + "/wiki/rest/api/content/search?cql="
                    + URLEncoder.encode(cql, StandardCharsets.UTF_8) + "&limit=50";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", basicAuthHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Confluence listPages failed (%d): %s", response.statusCode(), response.body());
                return Response.status(503).entity("{\"error\":\"Confluence request failed\"}").build();
            }

            var root = mapper.readTree(response.body());
            ArrayNode result = mapper.createArrayNode();
            for (var page : root.path("results")) {
                ObjectNode item = mapper.createObjectNode();
                item.put("pageId", page.path("id").asText(""));
                item.put("title", page.path("title").asText(""));
                result.add(item);
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.warnf("Failed to list Confluence pages in space %s: %s", spaceKey, e.getMessage());
            return Response.status(503).entity("{\"error\":\"Failed to list Confluence pages\"}").build();
        }
    }

    private String basicAuthHeader() {
        String user = confluenceService.getUser();
        String token = confluenceService.getApiToken();
        String encoded = java.util.Base64.getEncoder()
                .encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
