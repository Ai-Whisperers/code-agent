package com.eneve.agent.agent.service;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class JiraReviewContextBuilderTest {

    @Inject
    JiraReviewContextBuilder builder;

    @InjectMock
    JiraService jiraService;

    private static JiraIssueDetail detail(String key, String summary, String desc) {
        return new JiraIssueDetail(key, summary, desc, "In Progress", "alice", "bob",
                List.of("roadmap"), List.of(), List.of(), null);
    }

    // ── buildEpicContext ──────────────────────────────────────────────────────

    @Test
    void buildEpicContext_includesKeyAndSummary() {
        when(jiraService.fetchIssueDetail("PROJ-1"))
                .thenReturn(detail("PROJ-1", "Big epic", "Epic description here"));

        String ctx = builder.buildEpicContext("PROJ-1");

        assertTrue(ctx.contains("PROJ-1"));
        assertTrue(ctx.contains("Big epic"));
        assertTrue(ctx.contains("Epic description here"));
    }

    @Test
    void buildEpicContext_returnsNoDataFallbackWhenIssueNotFound() {
        when(jiraService.fetchIssueDetail("PROJ-999")).thenReturn(null);

        String ctx = builder.buildEpicContext("PROJ-999");

        assertTrue(ctx.contains("No Jira data available"));
        assertTrue(ctx.contains("PROJ-999"));
    }

    @Test
    void buildEpicContext_handlesNullDescription() {
        when(jiraService.fetchIssueDetail("PROJ-2"))
                .thenReturn(new JiraIssueDetail("PROJ-2", "No-desc epic", null,
                        "Open", null, null, List.of(), List.of(), List.of(), null));

        String ctx = builder.buildEpicContext("PROJ-2");

        assertTrue(ctx.contains("No description provided"));
    }

    @Test
    void buildEpicContext_truncatesLongDescription() {
        String longDesc = "x".repeat(3000);
        when(jiraService.fetchIssueDetail("PROJ-3"))
                .thenReturn(detail("PROJ-3", "Summary", longDesc));

        String ctx = builder.buildEpicContext("PROJ-3");

        assertTrue(ctx.contains("[truncated]"));
        // Context should not include all 3000 chars
        assertFalse(ctx.contains(longDesc));
    }

    @Test
    void buildEpicContext_includesCommentsUpToLimit() {
        List<String> comments = List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10", "c11", "c12");
        JiraIssueDetail detail = new JiraIssueDetail("PROJ-4", "Epic", "desc",
                "In Progress", "alice", null, List.of(), comments, List.of(), null);
        when(jiraService.fetchIssueDetail("PROJ-4")).thenReturn(detail);

        String ctx = builder.buildEpicContext("PROJ-4");

        // Should mention 2 more comments not shown (12 - 10)
        assertTrue(ctx.contains("more comments not shown"));
        assertTrue(ctx.contains("c1"));
        assertTrue(ctx.contains("c10"));
        assertFalse(ctx.contains("c11")); // beyond limit
    }

    // ── buildFeatureContext ───────────────────────────────────────────────────

    @Test
    void buildFeatureContext_includesParentEpicDescription() {
        when(jiraService.fetchIssueDetail("PROJ-10"))
                .thenReturn(detail("PROJ-10", "Feature A", "Feature desc"));
        when(jiraService.fetchIssueDetail("PROJ-1"))
                .thenReturn(detail("PROJ-1", "Parent Epic", "Epic desc for parent"));

        String ctx = builder.buildFeatureContext("PROJ-10", "PROJ-1");

        assertTrue(ctx.contains("Feature desc"));
        assertTrue(ctx.contains("Parent Epic Context"));
        assertTrue(ctx.contains("Epic desc for parent"));
    }

    @Test
    void buildFeatureContext_nullParentKeySkipsParentBlock() {
        when(jiraService.fetchIssueDetail("PROJ-10"))
                .thenReturn(detail("PROJ-10", "Feature A", "Feature desc"));

        String ctx = builder.buildFeatureContext("PROJ-10", null);

        assertFalse(ctx.contains("Parent Epic Context"));
        verify(jiraService, times(1)).fetchIssueDetail(anyString());
    }

    // ── buildUserStoryContext ─────────────────────────────────────────────────

    @Test
    void buildUserStoryContext_includesFeatureAndEpicDescriptions() {
        when(jiraService.fetchIssueDetail("PROJ-20"))
                .thenReturn(detail("PROJ-20", "Story", "Story desc"));
        when(jiraService.fetchIssueDetail("PROJ-10"))
                .thenReturn(detail("PROJ-10", "Feature", "Feature desc"));
        when(jiraService.fetchIssueDetail("PROJ-1"))
                .thenReturn(detail("PROJ-1", "Epic", "Epic desc"));

        String ctx = builder.buildUserStoryContext("PROJ-20", "PROJ-10", "PROJ-1");

        assertTrue(ctx.contains("Story desc"));
        assertTrue(ctx.contains("Parent Feature Context"));
        assertTrue(ctx.contains("Feature desc"));
        assertTrue(ctx.contains("Grandparent Epic Context"));
        assertTrue(ctx.contains("Epic desc"));
    }

    @Test
    void buildUserStoryContext_missingGrandparentDoesNotFail() {
        when(jiraService.fetchIssueDetail("PROJ-20"))
                .thenReturn(detail("PROJ-20", "Story", "Story desc"));
        when(jiraService.fetchIssueDetail("PROJ-10"))
                .thenReturn(detail("PROJ-10", "Feature", "Feature desc"));
        when(jiraService.fetchIssueDetail("PROJ-1")).thenReturn(null);

        String ctx = builder.buildUserStoryContext("PROJ-20", "PROJ-10", "PROJ-1");

        assertTrue(ctx.contains("Story desc"));
        assertTrue(ctx.contains("Feature desc"));
        assertFalse(ctx.contains("Grandparent Epic Context"));
    }
}
