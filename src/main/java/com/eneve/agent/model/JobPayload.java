package com.eneve.agent.model;

/**
 * Sealed marker interface for all job request payload types.
 *
 * <p>Callers can use pattern matching (Java 21+) to switch over the concrete type:
 * <pre>{@code
 *   switch (job.getPayload()) {
 *       case RunFixRequest r    -> ...
 *       case ReviewPrRequest r  -> ...
 *       // …
 *   }
 * }</pre>
 */
public sealed interface JobPayload
        permits RunFixRequest, ReviewPrRequest, FixPrRequest,
                ReplyCommentRequest, HookJobRequest,
                GenerateTestsRequest, GenerateDocsRequest,
                SyncConfluenceRequest, MetricsJobRequest,
                QualityReportJobRequest, JiraReviewRequest,
                PromoteRequest, SelfAnalysisRequest,
                GenerateArchitectureRequest, GenerateCloudArchitectureRequest,
                KnowledgeGraphRequest, TechDebtRequest {
}
