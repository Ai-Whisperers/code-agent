package com.eneve.agent.agent;

import com.eneve.agent.agent.model.RepoSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepoSettingsTest {

    @Test
    void constructorCreatesCorrectRecord() {
        Long id = 1L;
        String workspace = "test-workspace";
        String repoSlug = "test-repo";
        boolean reviewEnabled = true;
        List<String> ruleNames = List.of("rule1", "rule2");
        String reviewPrompt = "Custom review prompt";
        List<String> disabledHooks = List.of("hook1");
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt.plusSeconds(10);

        RepoSettings settings = new RepoSettings(id, workspace, repoSlug, reviewEnabled,
                false, false, false, false, false,
                ruleNames, reviewPrompt, disabledHooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                createdAt, updatedAt);

        assertEquals(id, settings.id());
        assertEquals(workspace, settings.workspace());
        assertEquals(repoSlug, settings.repoSlug());
        assertEquals(reviewEnabled, settings.reviewEnabled());
        assertEquals(ruleNames, settings.ruleNames());
        assertEquals(reviewPrompt, settings.reviewPrompt());
        assertEquals(disabledHooks, settings.disabledHooks());
        assertEquals(createdAt, settings.createdAt());
        assertEquals(updatedAt, settings.updatedAt());
    }

    @Test
    void defaultsFactoryMethodCreatesCorrectSettings() {
        String workspace = "default-workspace";
        String repoSlug = "default-repo";

        RepoSettings settings = RepoSettings.defaults(workspace, repoSlug);

        assertNull(settings.id());
        assertEquals(workspace, settings.workspace());
        assertEquals(repoSlug, settings.repoSlug());
        assertTrue(settings.reviewEnabled());
        assertNotNull(settings.ruleNames());
        assertTrue(settings.ruleNames().isEmpty());
        assertNull(settings.reviewPrompt());
        assertNotNull(settings.disabledHooks());
        assertTrue(settings.disabledHooks().isEmpty());
        assertNotNull(settings.createdAt());
        assertNotNull(settings.updatedAt());
    }

    @Test
    void defaultsFactoryMethodWithNullValues() {
        RepoSettings settings = RepoSettings.defaults(null, null);

        assertNull(settings.workspace());
        assertNull(settings.repoSlug());
        assertTrue(settings.reviewEnabled());
        assertNotNull(settings.ruleNames());
        assertTrue(settings.ruleNames().isEmpty());
        assertNull(settings.reviewPrompt());
        assertNotNull(settings.disabledHooks());
        assertTrue(settings.disabledHooks().isEmpty());
    }

    @Test
    void defaultsFactoryMethodWithEmptyStrings() {
        RepoSettings settings = RepoSettings.defaults("", "");

        assertEquals("", settings.workspace());
        assertEquals("", settings.repoSlug());
        assertTrue(settings.reviewEnabled());
    }

    @Test
    void defaultsFactoryMethodSetsTimestampsToNow() {
        Instant before = Instant.now().minusSeconds(1);
        RepoSettings settings = RepoSettings.defaults("workspace", "repo");
        Instant after = Instant.now().plusSeconds(1);

        assertTrue(settings.createdAt().isAfter(before));
        assertTrue(settings.createdAt().isBefore(after));
        assertTrue(settings.updatedAt().isAfter(before));
        assertTrue(settings.updatedAt().isBefore(after));
    }

    @Test
    void constructorWithNullValues() {
        RepoSettings settings = new RepoSettings(null, null, null, false,
                false, false, false, false, false,
                null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null);

        assertNull(settings.id());
        assertNull(settings.workspace());
        assertNull(settings.repoSlug());
        assertFalse(settings.reviewEnabled());
        assertNull(settings.ruleNames());
        assertNull(settings.reviewPrompt());
        assertNull(settings.disabledHooks());
        assertNull(settings.createdAt());
        assertNull(settings.updatedAt());
    }

    @Test
    void constructorWithEmptyLists() {
        List<String> emptyRules = new ArrayList<>();
        List<String> emptyHooks = new ArrayList<>();

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                emptyRules, "prompt", emptyHooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals(emptyRules, settings.ruleNames());
        assertEquals(emptyHooks, settings.disabledHooks());
        assertTrue(settings.ruleNames().isEmpty());
        assertTrue(settings.disabledHooks().isEmpty());
    }

    @Test
    void constructorWithMultipleRules() {
        List<String> rules = List.of("checkstyle", "pmd", "spotbugs", "eslint", "sonarqube");

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                rules, null, List.of(),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals(5, settings.ruleNames().size());
        assertTrue(settings.ruleNames().contains("checkstyle"));
        assertTrue(settings.ruleNames().contains("pmd"));
        assertTrue(settings.ruleNames().contains("spotbugs"));
        assertTrue(settings.ruleNames().contains("eslint"));
        assertTrue(settings.ruleNames().contains("sonarqube"));
    }

    @Test
    void constructorWithMultipleDisabledHooks() {
        List<String> disabledHooks = List.of("pre-commit", "pre-push", "post-merge");

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                List.of(), null, disabledHooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals(3, settings.disabledHooks().size());
        assertTrue(settings.disabledHooks().contains("pre-commit"));
        assertTrue(settings.disabledHooks().contains("pre-push"));
        assertTrue(settings.disabledHooks().contains("post-merge"));
    }

    @Test
    void constructorWithCustomReviewPrompt() {
        String customPrompt = "Please review this code with special attention to " +
                "security vulnerabilities and performance optimizations. " +
                "Follow our team's coding standards and ensure proper error handling.";

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                List.of("security"), customPrompt, List.of(),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals(customPrompt, settings.reviewPrompt());
    }

    @Test
    void reviewEnabledBooleanValues() {
        RepoSettings enabledSettings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                List.of(), null, List.of(),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());
        RepoSettings disabledSettings = new RepoSettings(2L, "workspace", "repo", false,
                false, false, false, false, false,
                List.of(), null, List.of(),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertTrue(enabledSettings.reviewEnabled());
        assertFalse(disabledSettings.reviewEnabled());
    }

    @Test
    void constructorWithDifferentTimestamps() {
        Instant createdAt = Instant.now().minusSeconds(3600); // 1 hour ago
        Instant updatedAt = Instant.now(); // now

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                List.of(), null, List.of(),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                createdAt, updatedAt);

        assertEquals(createdAt, settings.createdAt());
        assertEquals(updatedAt, settings.updatedAt());
        assertTrue(settings.updatedAt().isAfter(settings.createdAt()));
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        List<String> rules = List.of("rule1");
        List<String> hooks = List.of("hook1");

        RepoSettings settings1 = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                rules, "prompt", hooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                now, now);
        RepoSettings settings2 = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                rules, "prompt", hooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                now, now);
        RepoSettings settings3 = new RepoSettings(2L, "workspace", "repo", true,
                false, false, false, false, false,
                rules, "prompt", hooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                now, now);

        assertEquals(settings1, settings2);
        assertNotEquals(settings1, settings3);
        assertEquals(settings1.hashCode(), settings2.hashCode());
    }

    @Test
    void recordToString() {
        RepoSettings settings = new RepoSettings(1L, "my-workspace", "my-repo", true,
                false, false, false, false, false,
                List.of("checkstyle"), "Review carefully", List.of("pre-commit"),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        String toString = settings.toString();
        assertTrue(toString.contains("my-workspace"));
        assertTrue(toString.contains("my-repo"));
        assertTrue(toString.contains("true"));
        assertTrue(toString.contains("checkstyle"));
        assertTrue(toString.contains("Review carefully"));
        assertTrue(toString.contains("pre-commit"));
    }

    @Test
    void listFieldsAreImmutableReferences() {
        List<String> originalRules = List.of("rule1", "rule2");
        List<String> originalHooks = List.of("hook1");

        RepoSettings settings = new RepoSettings(1L, "workspace", "repo", true,
                false, false, false, false, false,
                originalRules, null, originalHooks,
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals(originalRules, settings.ruleNames());
        assertEquals(originalHooks, settings.disabledHooks());
        assertEquals(2, settings.ruleNames().size());
        assertEquals(1, settings.disabledHooks().size());
    }

    @Test
    void defaultsFactoryCreatesImmutableLists() {
        RepoSettings settings = RepoSettings.defaults("workspace", "repo");

        assertNotNull(settings.ruleNames());
        assertNotNull(settings.disabledHooks());
        assertEquals(0, settings.ruleNames().size());
        assertEquals(0, settings.disabledHooks().size());
    }

    @Test
    void constructorWithSpecialCharactersInStrings() {
        RepoSettings settings = new RepoSettings(1L, "workspace-123", "repo_name.git",
                true, false, false, false, false, false,
                List.of("rule-1", "rule_2"), "Review with @mentions and #tags",
                List.of("hook/pre-commit"),
                null, null, null, null, null, Map.of(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now());

        assertEquals("workspace-123", settings.workspace());
        assertEquals("repo_name.git", settings.repoSlug());
        assertTrue(settings.ruleNames().contains("rule-1"));
        assertTrue(settings.ruleNames().contains("rule_2"));
        assertEquals("Review with @mentions and #tags", settings.reviewPrompt());
        assertTrue(settings.disabledHooks().contains("hook/pre-commit"));
    }

    @Test
    void defaultSettingsHaveExpectedDefaults() {
        RepoSettings settings = RepoSettings.defaults("workspace", "repo");

        assertNull(settings.id());
        assertTrue(settings.reviewEnabled());
        assertNotNull(settings.ruleNames());
        assertTrue(settings.ruleNames().isEmpty());
        assertNull(settings.reviewPrompt());
        assertNotNull(settings.disabledHooks());
        assertTrue(settings.disabledHooks().isEmpty());
        assertNotNull(settings.createdAt());
        assertNotNull(settings.updatedAt());
    }
}
