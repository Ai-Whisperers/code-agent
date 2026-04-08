package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.architecture.ArchitectureDiagramStore;
import com.eneve.agent.architecture.ArchitectureDiagramStore.CloudEnvironmentKey;
import com.eneve.agent.architecture.ArchitectureDiagramVersion;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.GenerateArchitectureRequest;
import com.eneve.agent.model.GenerateCloudArchitectureRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.ProductConfig;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequestScoped
@Authenticated
@Path("/architecture")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Architecture", description = "Architecture diagram generation and versioned storage")
public class ArchitectureResource {

    private static final Logger LOG = Logger.getLogger(ArchitectureResource.class);

    @Inject ArchitectureDiagramStore store;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject CustomerRegistryStore customerRegistryStore;

    // ── Repo architecture ─────────────────────────────────────────────────────

    @POST
    @Path("/generate")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "generateArchitecture",
            summary = "Queue an architecture generation job for a repository",
            description = "Clones the repository, runs the AI agent to produce a Structurizr DSL model, "
                    + "validates it, exports to Mermaid, and stores versioned diagrams. "
                    + "If a pinned version exists it is used as the AI baseline. "
                    + "Returns immediately with a jobId for polling via GET /status/{jobId}."
    )
    public Response generateArchitecture(GenerateArchitectureRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }

        String branchName = request.branchName();
        if (!request.isCommitDirect() && (branchName == null || branchName.isBlank())) {
            branchName = "agent/generate-architecture";
        }

        GenerateArchitectureRequest effective = new GenerateArchitectureRequest(
                request.repoUrl(), branchName, request.targetBranch(), request.commitDirect());

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, effective);
        jobStore.put(job);

        try {
            if (!jobQueue.submit(job)) {
                throw new JobQueueFullException("Job queue is full");
            }
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }

        LOG.infof("GenerateArchitecture job %s accepted for %s", jobId, request.repoUrl());
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    @GET
    @Operation(
            operationId = "getArchitectureDiagrams",
            summary = "Get current architecture diagrams for a repository",
            description = "Returns the current (pinned or latest) version of each view for the given repo."
    )
    public Response getArchitectureDiagrams(@QueryParam("repo") String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) {
            return Response.status(400).entity(Map.of("error", "repo query parameter is required")).build();
        }
        List<ArchitectureDiagramVersion> diagrams = store.findCurrentVersions(repoSlug);
        return Response.ok(diagrams).build();
    }

    @GET
    @Path("/repos")
    @Operation(
            operationId = "listArchitectureRepos",
            summary = "List repositories that have architecture diagrams"
    )
    public Response listRepos() {
        return Response.ok(store.listRepoSlugs()).build();
    }

    @GET
    @Path("/{viewId}/versions")
    @Operation(
            operationId = "getArchitectureVersions",
            summary = "Get version history for a specific diagram view"
    )
    public Response getVersions(
            @PathParam("viewId") long viewId,
            @QueryParam("repo") String repoSlug,
            @QueryParam("viewName") String viewName) {
        if (repoSlug != null && viewName != null) {
            return Response.ok(store.listVersions(repoSlug, viewName)).build();
        }
        // Fall back to fetching the row to get scope info
        return store.findById(viewId)
                .map(v -> {
                    if (v.repoSlug() != null) {
                        return Response.ok(store.listVersions(v.repoSlug(), v.viewName())).build();
                    } else {
                        return Response.ok(store.listCloudVersions(
                                v.customerId(), v.environment(), v.viewName())).build();
                    }
                })
                .orElse(Response.status(404).entity(Map.of("error", "Version not found")).build());
    }

    @PUT
    @Path("/{viewId}/dsl")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "saveArchitectureDsl",
            summary = "Save a human-edited DSL as a new version",
            description = "Inserts a new version row with source='human'. Does not affect the pinned version."
    )
    public Response saveDsl(@PathParam("viewId") long viewId, Map<String, String> body) {
        String dslContent = body.get("dslSrc");
        if (dslContent == null || dslContent.isBlank()) {
            return Response.status(400).entity(Map.of("error", "dslSrc is required")).build();
        }

        return store.findById(viewId)
                .map(v -> {
                    long newId;
                    if (v.repoSlug() != null) {
                        newId = store.insertRepoVersion(v.repoSlug(), v.viewName(), v.viewType(),
                                "human", dslContent, v.mermaidSrc());
                    } else {
                        newId = store.insertCloudVersion(v.customerId(), v.environment(),
                                v.viewName(), v.viewType(), "human", dslContent, v.mermaidSrc());
                    }
                    return Response.ok(Map.of("id", newId)).build();
                })
                .orElse(Response.status(404).entity(Map.of("error", "Version not found")).build());
    }

    @POST
    @Path("/{viewId}/pin")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "pinArchitectureVersion",
            summary = "Pin a version as the AI baseline for the next generation run",
            description = "Unpins any previously pinned version for the same scope+viewName."
    )
    public Response pin(@PathParam("viewId") long viewId) {
        boolean ok = store.pin(viewId);
        return ok
                ? Response.ok(Map.of("pinned", true)).build()
                : Response.status(404).entity(Map.of("error", "Version not found")).build();
    }

    @POST
    @Path("/{viewId}/unpin")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "unpinArchitectureVersion", summary = "Unpin a version")
    public Response unpin(@PathParam("viewId") long viewId) {
        boolean ok = store.unpin(viewId);
        return ok
                ? Response.ok(Map.of("pinned", false)).build()
                : Response.status(404).entity(Map.of("error", "Version not found")).build();
    }

    @GET
    @Path("/{viewId}/export")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(
            operationId = "exportArchitectureVersionDsl",
            summary = "Download the DSL of a specific version as a .dsl file"
    )
    public Response exportVersion(@PathParam("viewId") long viewId) {
        return store.findById(viewId)
                .map(v -> {
                    String filename = buildFilename(v) + "-v" + v.version() + ".dsl";
                    return Response.ok(v.dslSrc().getBytes(StandardCharsets.UTF_8))
                            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .build();
                })
                .orElse(Response.status(404).entity("Version not found").build());
    }

    @GET
    @Path("/export")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(
            operationId = "exportArchitectureDsl",
            summary = "Download the current (pinned or latest) DSL for a repo as a .dsl file"
    )
    public Response exportRepo(@QueryParam("repo") String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) {
            return Response.status(400).entity("repo query parameter is required").build();
        }
        return store.findPinnedDsl(repoSlug)
                .map(dsl -> {
                    String filename = repoSlug + "-architecture.dsl";
                    return Response.ok(dsl.getBytes(StandardCharsets.UTF_8))
                            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .build();
                })
                .orElse(Response.status(404).entity("No diagrams found for repo: " + repoSlug).build());
    }

    // ── Bulk generation (admin) ───────────────────────────────────────────────

    @POST
    @Path("/generate-all")
    @RolesAllowed("app_admin")
    @Operation(
            operationId = "generateAllArchitecture",
            summary = "Queue architecture generation for all known repos and all customer cloud environments",
            description = "Admin-only. Queues one GENERATE_ARCHITECTURE job per known repo URL "
                    + "and one GENERATE_CLOUD_ARCHITECTURE job per customer environment. "
                    + "Returns counts of queued and skipped (queue-full) jobs."
    )
    public Response generateAll() {
        int repoQueued = 0, repoSkipped = 0;
        int cloudQueued = 0, cloudSkipped = 0;

        // ── Repo architecture ──────────────────────────────────────────────
        // Build URL list from the product registry (canonical) + stored diagram URLs (fallback).
        java.util.LinkedHashSet<String> repoUrlSet = new java.util.LinkedHashSet<>();
        for (ProductConfig product : customerRegistryStore.listAllProducts()) {
            if (product.git() == null || product.git().repos() == null
                    || product.git().workspace() == null) continue;
            for (String repoSlug : product.git().repos()) {
                try {
                    String url = buildRepoUrl(product.git().platform(),
                            product.git().workspace(), repoSlug, product.git().baseUrl());
                    if (url != null) repoUrlSet.add(url);
                } catch (Exception e) {
                    LOG.warnf("generate-all: could not build URL for product=%s repo=%s: %s",
                            product.productId(), repoSlug, e.getMessage());
                }
            }
        }
        repoUrlSet.addAll(store.listRepoUrls()); // also include repos not in registry

        for (String repoUrl : repoUrlSet) {
            try {
                GenerateArchitectureRequest req = new GenerateArchitectureRequest(
                        repoUrl, "agent/generate-architecture", null, false);
                String jobId = UUID.randomUUID().toString();
                JobRecord job = new JobRecord(jobId, req);
                jobStore.put(job);
                if (jobQueue.submit(job)) {
                    repoQueued++;
                    LOG.infof("generate-all: queued repo job %s for %s", jobId, repoUrl);
                } else {
                    repoSkipped++;
                    LOG.warnf("generate-all: queue full, skipped repo %s", repoUrl);
                }
            } catch (Exception e) {
                repoSkipped++;
                LOG.warnf("generate-all: failed to queue repo %s: %s", repoUrl, e.getMessage());
            }
        }

        // ── Cloud architecture ─────────────────────────────────────────────
        List<CustomerConfig> customers = customerRegistryStore.listCustomers();
        for (CustomerConfig customer : customers) {
            if (customer.environments() == null) continue;
            for (EnvironmentConfig env : customer.environments()) {
                if (env.aws() == null) continue;
                String envLabel = (env.name() != null && !env.name().isBlank()) ? env.name()
                        : (env.type() != null && !env.type().isBlank()) ? env.type() : null;
                if (envLabel == null) continue;
                try {
                    GenerateCloudArchitectureRequest req = new GenerateCloudArchitectureRequest(
                            customer.customerId(), envLabel);
                    String jobId = UUID.randomUUID().toString();
                    JobRecord job = new JobRecord(jobId, req);
                    jobStore.put(job);
                    if (jobQueue.submit(job)) {
                        cloudQueued++;
                        LOG.infof("generate-all: queued cloud job %s for %s/%s", jobId, customer.customerId(), envLabel);
                    } else {
                        cloudSkipped++;
                        LOG.warnf("generate-all: queue full, skipped cloud %s/%s", customer.customerId(), envLabel);
                    }
                } catch (Exception e) {
                    cloudSkipped++;
                    LOG.warnf("generate-all: failed to queue cloud %s/%s: %s",
                            customer.customerId(), envLabel, e.getMessage());
                }
            }
        }

        LOG.infof("generate-all complete: repos queued=%d skipped=%d, cloud queued=%d skipped=%d",
                repoQueued, repoSkipped, cloudQueued, cloudSkipped);
        return Response.accepted(Map.of(
                "reposQueued", repoQueued,
                "reposSkipped", repoSkipped,
                "cloudQueued", cloudQueued,
                "cloudSkipped", cloudSkipped
        )).build();
    }

    // ── Cloud architecture ────────────────────────────────────────────────────

    @POST
    @Path("/cloud/generate")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "generateCloudArchitecture",
            summary = "Queue a cloud architecture discovery job for a customer environment",
            description = "Queries AWS ECS and RDS to discover running services and databases, "
                    + "builds a Structurizr DSL model, and stores versioned diagrams. "
                    + "Human-edited elements tagged !human in the pinned DSL are preserved."
    )
    public Response generateCloudArchitecture(GenerateCloudArchitectureRequest request) {
        if (request.customerId() == null || request.customerId().isBlank()) {
            return Response.status(400).entity(Map.of("error", "customerId is required")).build();
        }
        if (request.environmentName() == null || request.environmentName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "environmentName is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        try {
            if (!jobQueue.submit(job)) {
                throw new JobQueueFullException("Job queue is full");
            }
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }

        LOG.infof("GenerateCloudArchitecture job %s accepted for customer=%s env=%s",
                jobId, request.customerId(), request.environmentName());
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    @GET
    @Path("/cloud")
    @Operation(
            operationId = "getCloudArchitectureDiagrams",
            summary = "Get current cloud architecture diagrams for a customer environment"
    )
    public Response getCloudDiagrams(
            @QueryParam("customerId") String customerId,
            @QueryParam("environment") String environment) {
        if (customerId == null || customerId.isBlank() || environment == null || environment.isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "customerId and environment query parameters are required"))
                    .build();
        }
        return Response.ok(store.findCurrentCloudVersions(customerId, environment)).build();
    }

    @GET
    @Path("/cloud/environments")
    @Operation(
            operationId = "listCloudEnvironments",
            summary = "List customer/environment pairs that have cloud architecture diagrams"
    )
    public Response listCloudEnvironments() {
        List<CloudEnvironmentKey> envs = store.listCloudEnvironments();
        return Response.ok(envs).build();
    }

    @GET
    @Path("/cloud/export")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(
            operationId = "exportCloudArchitectureDsl",
            summary = "Download the current (pinned or latest) DSL for a cloud environment as a .dsl file"
    )
    public Response exportCloud(
            @QueryParam("customerId") String customerId,
            @QueryParam("environment") String environment) {
        if (customerId == null || customerId.isBlank() || environment == null || environment.isBlank()) {
            return Response.status(400).entity("customerId and environment query parameters are required").build();
        }
        return store.findPinnedCloudDsl(customerId, environment)
                .map(dsl -> {
                    String filename = customerId + "-" + environment + "-architecture.dsl";
                    return Response.ok(dsl.getBytes(StandardCharsets.UTF_8))
                            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .build();
                })
                .orElse(Response.status(404)
                        .entity("No diagrams found for " + customerId + "/" + environment).build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a canonical HTTPS repo URL from a GitConfig platform/workspace/repo tuple.
     * Returns null if the platform is unrecognised or inputs are blank.
     */
    private static String buildRepoUrl(String platform, String workspace, String repoSlug, String baseUrl) {
        if (platform == null || workspace == null || repoSlug == null) return null;
        return switch (platform.toLowerCase()) {
            case "bitbucket"   -> "https://bitbucket.org/" + workspace + "/" + repoSlug + ".git";
            case "github"      -> "https://github.com/" + workspace + "/" + repoSlug + ".git";
            case "gitlab"      -> {
                String base = (baseUrl != null && !baseUrl.isBlank())
                        ? baseUrl.replaceAll("/$", "") : "https://gitlab.com";
                yield base + "/" + workspace + "/" + repoSlug + ".git";
            }
            case "azuredevops" -> "https://dev.azure.com/" + workspace + "/_git/" + repoSlug;
            default -> null;
        };
    }

    private static String buildFilename(ArchitectureDiagramVersion v) {
        if (v.repoSlug() != null) return v.repoSlug() + "-" + v.viewName();
        return v.customerId() + "-" + v.environment() + "-" + v.viewName();
    }
}
