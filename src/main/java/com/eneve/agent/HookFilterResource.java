package com.eneve.agent;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Comparator;
import java.util.List;

/**
 * Provides autocomplete data for hook trigger filter configuration.
 * Returns lightweight option lists for use in the hook editor UI.
 */
@Path("/api/hooks/autocomplete")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Hook Filters", description = "Autocomplete data for hook trigger filter configuration")
public class HookFilterResource {

    private static final Logger LOG = Logger.getLogger(HookFilterResource.class);

    @Inject
    RepoSettingsStore repoSettingsStore;

    public record RepoOption(String value, String workspace, String repoSlug, String displayName) {}

    @GET
    @Path("/repositories")
    @Operation(
            operationId = "getRepositoriesForFilter",
            summary = "Get non-archived repositories for hook filter autocomplete",
            description = "Returns all active (non-archived) repositories from repo settings, "
                    + "suitable for use in hook trigger filter autocomplete."
    )
    @APIResponse(responseCode = "200", description = "List of active repositories")
    public Response getRepositories() {
        try {
            List<RepoOption> repos = repoSettingsStore.listAll().stream()
                    .filter(r -> !r.archived())
                    .map(r -> new RepoOption(
                            r.repoSlug(),
                            r.workspace(),
                            r.repoSlug(),
                            r.workspace() + "/" + r.repoSlug()
                    ))
                    .sorted(Comparator.comparing(RepoOption::displayName))
                    .toList();

            return Response.ok(repos).build();
        } catch (Exception e) {
            LOG.errorf("Failed to fetch repositories for autocomplete: %s", e.getMessage());
            return Response.serverError()
                    .entity(java.util.Map.of("error", "Failed to fetch repositories"))
                    .build();
        }
    }
}
