package com.eneve.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.store.CommentStore;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoint exposing review quality metrics for a repository:
 * total findings, resolution rate, false-positive rate, and auto-suppressed patterns.
 */
@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Review Metrics", description = "Review quality and false-positive rate metrics per repository")
public class ReviewMetricsResource {

    @Inject
    CommentStore commentStore;

    @Inject
    CommentFeedbackStore feedbackStore;

    @ConfigProperty(name = "review.fp.auto-suppress-threshold", defaultValue = "3")
    int fpAutoSuppressThreshold;

    @GET
    @Path("/review-quality/{workspace}/{repoSlug}")
    @Operation(
            operationId = "getReviewQualityMetrics",
            summary = "Get review quality metrics for a repository",
            description = "Returns total findings, resolution rate, false-positive rate broken down by "
                    + "category, and the count of auto-suppressed noise patterns."
    )
    @APIResponse(responseCode = "200", description = "Metrics for the repository")
    public Response getMetrics(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        long totalFindings = commentStore.countTotalFindings(workspace, repoSlug);
        long resolvedFindings = commentStore.countResolvedFindings(workspace, repoSlug);
        long falsePositives = feedbackStore.countFalsePositives(workspace, repoSlug);
        Map<String, Long> fpByCategory = feedbackStore.countFalsePositivesByCategory(workspace, repoSlug);
        int autoSuppressedPatterns = feedbackStore.findRecurringPatterns(workspace, repoSlug, fpAutoSuppressThreshold).size();

        double fpRate = totalFindings > 0
                ? Math.round((double) falsePositives / totalFindings * 10000.0) / 10000.0
                : 0.0;
        double resolutionRate = totalFindings > 0
                ? Math.round((double) resolvedFindings / totalFindings * 10000.0) / 10000.0
                : 0.0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", workspace);
        body.put("repoSlug", repoSlug);
        body.put("totalFindings", totalFindings);
        body.put("resolvedByDeveloper", resolvedFindings);
        body.put("resolutionRate", resolutionRate);
        body.put("falsePositives", falsePositives);
        body.put("fpRate", fpRate);
        body.put("fpByCategory", fpByCategory);
        body.put("autoSuppressedPatterns", autoSuppressedPatterns);

        return Response.ok(body).build();
    }
}
