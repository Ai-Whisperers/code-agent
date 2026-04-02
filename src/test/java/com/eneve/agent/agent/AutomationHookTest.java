package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutomationHookTest {

    @Test
    void constructorCreatesCorrectRecord() {
        Long id = 1L;
        String name = "Daily Security Check";
        String description = "Runs security analysis daily";
        boolean enabled = true;
        String triggerType = "SCHEDULE";
        String prEvent = "opened";
        String branchPattern = "main,develop";
        String cronExpr = "0 0 2 * * ?";
        String actionType = "REVIEW";
        String prompt = "Focus on security vulnerabilities";
        List<String> ruleNames = List.of("security", "performance");
        String extraRules = "Check for SQL injection";
        String targetBranch = "main";
        boolean commitDirect = false;
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt.plusSeconds(10);

        AutomationHook hook = new AutomationHook(id, name, description, enabled, 
                triggerType, prEvent, branchPattern, cronExpr, actionType, prompt, 
                ruleNames, extraRules, targetBranch, commitDirect, createdAt, updatedAt);

        assertEquals(id, hook.id());
        assertEquals(name, hook.name());
        assertEquals(description, hook.description());
        assertEquals(enabled, hook.enabled());
        assertEquals(triggerType, hook.triggerType());
        assertEquals(prEvent, hook.prEvent());
        assertEquals(branchPattern, hook.branchPattern());
        assertEquals(cronExpr, hook.cronExpr());
        assertEquals(actionType, hook.actionType());
        assertEquals(prompt, hook.prompt());
        assertEquals(ruleNames, hook.ruleNames());
        assertEquals(extraRules, hook.extraRules());
        assertEquals(targetBranch, hook.targetBranch());
        assertEquals(commitDirect, hook.commitDirect());
        assertEquals(createdAt, hook.createdAt());
        assertEquals(updatedAt, hook.updatedAt());
    }

    @Test
    void constructorWithNullValues() {
        AutomationHook hook = new AutomationHook(null, null, null, false, 
                null, null, null, null, null, null, null, null, null, 
                false, null, null);

        assertNull(hook.id());
        assertNull(hook.name());
        assertNull(hook.description());
        assertFalse(hook.enabled());
        assertNull(hook.triggerType());
        assertNull(hook.prEvent());
        assertNull(hook.branchPattern());
        assertNull(hook.cronExpr());
        assertNull(hook.actionType());
        assertNull(hook.prompt());
        assertNull(hook.ruleNames());
        assertNull(hook.extraRules());
        assertNull(hook.targetBranch());
        assertFalse(hook.commitDirect());
        assertNull(hook.createdAt());
        assertNull(hook.updatedAt());
    }

    @Test
    void constructorWithEmptyStrings() {
        AutomationHook hook = new AutomationHook(1L, "", "", true, "", "", "", "", 
                "", "", List.of(), "", "", false, Instant.now(), Instant.now());

        assertEquals("", hook.name());
        assertEquals("", hook.description());
        assertEquals("", hook.triggerType());
        assertEquals("", hook.prEvent());
        assertEquals("", hook.branchPattern());
        assertEquals("", hook.cronExpr());
        assertEquals("", hook.actionType());
        assertEquals("", hook.prompt());
        assertEquals("", hook.extraRules());
        assertEquals("", hook.targetBranch());
    }

    @Test
    void constructorWithPullRequestTrigger() {
        AutomationHook hook = new AutomationHook(1L, "PR Hook", "Triggers on PR events", 
                true, "PR_EVENT", "opened", "feature/*", null, "REVIEW", 
                "Review the changes", List.of("code-quality"), null, 
                "develop", false, Instant.now(), Instant.now());

        assertEquals("PR_EVENT", hook.triggerType());
        assertEquals("opened", hook.prEvent());
        assertEquals("feature/*", hook.branchPattern());
        assertNull(hook.cronExpr());
        assertEquals("REVIEW", hook.actionType());
    }

    @Test
    void constructorWithScheduleTrigger() {
        AutomationHook hook = new AutomationHook(1L, "Scheduled Hook", "Runs on schedule", 
                true, "SCHEDULE", null, "main", "0 0 1 * * ?", "FIX", 
                "Daily automated fixes", List.of("maintenance"), 
                "Apply automated fixes", "main", true, Instant.now(), Instant.now());

        assertEquals("SCHEDULE", hook.triggerType());
        assertNull(hook.prEvent());
        assertEquals("main", hook.branchPattern());
        assertEquals("0 0 1 * * ?", hook.cronExpr());
        assertEquals("FIX", hook.actionType());
        assertTrue(hook.commitDirect());
    }

    @Test
    void constructorWithMultipleRules() {
        List<String> rules = List.of("security", "performance", "maintainability", 
                "reliability", "code-smells");

        AutomationHook hook = new AutomationHook(1L, "Multi-Rule Hook", "Uses multiple rules", 
                true, "PR_EVENT", "synchronize", "*", null, "REVIEW", 
                "Comprehensive review", rules, null, "main", false, 
                Instant.now(), Instant.now());

        assertEquals(5, hook.ruleNames().size());
        assertTrue(hook.ruleNames().contains("security"));
        assertTrue(hook.ruleNames().contains("performance"));
        assertTrue(hook.ruleNames().contains("maintainability"));
        assertTrue(hook.ruleNames().contains("reliability"));
        assertTrue(hook.ruleNames().contains("code-smells"));
    }

    @Test
    void constructorWithComplexBranchPatterns() {
        String[] branchPatterns = {
            "main",
            "develop,staging",
            "feature/*",
            "hotfix/*,bugfix/*",
            "release/v*",
            "*"
        };

        for (String pattern : branchPatterns) {
            AutomationHook hook = new AutomationHook(1L, "Branch Hook", "Pattern test", 
                    true, "PR_EVENT", "opened", pattern, null, "REVIEW", 
                    "Pattern review", List.of(), null, "main", false, 
                    Instant.now(), Instant.now());
            assertEquals(pattern, hook.branchPattern());
        }
    }

    @Test
    void constructorWithDifferentActionTypes() {
        String[] actionTypes = {"REVIEW", "FIX", "ANALYZE", "TEST", "DEPLOY"};

        for (String actionType : actionTypes) {
            AutomationHook hook = new AutomationHook(1L, "Action Hook", "Action test", 
                    true, "PR_EVENT", "opened", "main", null, actionType, 
                    "Action prompt", List.of(), null, "main", false, 
                    Instant.now(), Instant.now());
            assertEquals(actionType, hook.actionType());
        }
    }

    @Test
    void constructorWithDifferentPrEvents() {
        String[] prEvents = {"opened", "synchronize", "closed", "merged", "reopened"};

        for (String prEvent : prEvents) {
            AutomationHook hook = new AutomationHook(1L, "PR Event Hook", "PR test", 
                    true, "PR_EVENT", prEvent, "main", null, "REVIEW", 
                    "Event prompt", List.of(), null, "main", false, 
                    Instant.now(), Instant.now());
            assertEquals(prEvent, hook.prEvent());
        }
    }

    @Test
    void constructorWithCronExpressions() {
        String[] cronExprs = {
            "0 0 2 * * ?",      // Daily at 2 AM
            "0 0 12 * * MON",   // Every Monday at noon
            "0 */15 * * * ?",   // Every 15 minutes
            "0 0 0 1 * ?",      // First day of every month
            "0 0 6 * * MON-FRI" // Weekdays at 6 AM
        };

        for (String cronExpr : cronExprs) {
            AutomationHook hook = new AutomationHook(1L, "Cron Hook", "Cron test", 
                    true, "SCHEDULE", null, "main", cronExpr, "REVIEW", 
                    "Scheduled prompt", List.of(), null, "main", false, 
                    Instant.now(), Instant.now());
            assertEquals(cronExpr, hook.cronExpr());
        }
    }

    @Test
    void constructorWithLongPrompt() {
        String longPrompt = "This is a very detailed prompt that provides comprehensive " +
                "instructions for the automation hook. It includes specific guidelines " +
                "for code review, security analysis, performance optimization, and " +
                "maintainability improvements. The prompt should be thorough enough " +
                "to guide the automated process effectively.";

        AutomationHook hook = new AutomationHook(1L, "Detailed Hook", "Detailed test", 
                true, "PR_EVENT", "opened", "main", null, "REVIEW", longPrompt, 
                List.of(), null, "main", false, Instant.now(), Instant.now());

        assertEquals(longPrompt, hook.prompt());
    }

    @Test
    void constructorWithExtraRules() {
        String extraRules = "1. Check for proper error handling\n" +
                "2. Validate input parameters\n" +
                "3. Ensure proper logging\n" +
                "4. Verify unit test coverage";

        AutomationHook hook = new AutomationHook(1L, "Extra Rules Hook", "Extra rules test", 
                true, "PR_EVENT", "opened", "main", null, "REVIEW", "Standard prompt", 
                List.of("standard"), extraRules, "main", false, Instant.now(), Instant.now());

        assertEquals(extraRules, hook.extraRules());
    }

    @Test
    void booleanFieldsHandleBothValues() {
        AutomationHook enabledHook = new AutomationHook(1L, "Enabled Hook", "Enabled", 
                true, "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", 
                List.of(), null, "main", false, Instant.now(), Instant.now());
        
        AutomationHook disabledHook = new AutomationHook(2L, "Disabled Hook", "Disabled", 
                false, "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", 
                List.of(), null, "main", true, Instant.now(), Instant.now());

        assertTrue(enabledHook.enabled());
        assertFalse(enabledHook.commitDirect());
        assertFalse(disabledHook.enabled());
        assertTrue(disabledHook.commitDirect());
    }

    @Test
    void constructorWithDifferentTargetBranches() {
        String[] targetBranches = {"main", "master", "develop", "staging", "production"};

        for (String targetBranch : targetBranches) {
            AutomationHook hook = new AutomationHook(1L, "Target Hook", "Target test", 
                    true, "PR_EVENT", "opened", "feature/*", null, "FIX", 
                    "Fix prompt", List.of(), null, targetBranch, false, 
                    Instant.now(), Instant.now());
            assertEquals(targetBranch, hook.targetBranch());
        }
    }

    @Test
    void constructorWithDifferentTimestamps() {
        Instant createdAt = Instant.now().minusSeconds(7200); // 2 hours ago
        Instant updatedAt = Instant.now(); // now

        AutomationHook hook = new AutomationHook(1L, "Time Hook", "Time test", 
                true, "SCHEDULE", null, "main", "0 0 12 * * ?", "REVIEW", 
                "Time prompt", List.of(), null, "main", false, createdAt, updatedAt);

        assertEquals(createdAt, hook.createdAt());
        assertEquals(updatedAt, hook.updatedAt());
        assertTrue(hook.updatedAt().isAfter(hook.createdAt()));
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        List<String> rules = List.of("rule1");

        AutomationHook hook1 = new AutomationHook(1L, "Hook", "Description", true, 
                "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", rules, 
                null, "main", false, now, now);
        AutomationHook hook2 = new AutomationHook(1L, "Hook", "Description", true, 
                "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", rules, 
                null, "main", false, now, now);
        AutomationHook hook3 = new AutomationHook(2L, "Hook", "Description", true, 
                "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", rules, 
                null, "main", false, now, now);

        assertEquals(hook1, hook2);
        assertNotEquals(hook1, hook3);
        assertEquals(hook1.hashCode(), hook2.hashCode());
    }

    @Test
    void recordToString() {
        AutomationHook hook = new AutomationHook(1L, "Test Hook", "Test Description", 
                true, "PR_EVENT", "opened", "main", null, "REVIEW", "Test Prompt", 
                List.of("security"), "Extra rules", "develop", false, 
                Instant.now(), Instant.now());
        
        String toString = hook.toString();
        assertTrue(toString.contains("Test Hook"));
        assertTrue(toString.contains("Test Description"));
        assertTrue(toString.contains("true"));
        assertTrue(toString.contains("PR_EVENT"));
        assertTrue(toString.contains("opened"));
        assertTrue(toString.contains("main"));
        assertTrue(toString.contains("REVIEW"));
        assertTrue(toString.contains("Test Prompt"));
        assertTrue(toString.contains("security"));
        assertTrue(toString.contains("Extra rules"));
        assertTrue(toString.contains("develop"));
        assertTrue(toString.contains("false"));
    }

    @Test
    void constructorWithSpecialCharactersInStrings() {
        AutomationHook hook = new AutomationHook(1L, "Hook-Name_123", 
                "Description with @mentions and #tags", true, "PR_EVENT", 
                "opened", "feature/JIRA-123", "0 0 12 * * ?", "REVIEW", 
                "Prompt with $variables and {placeholders}", 
                List.of("rule-1", "rule_2"), "Rules with: colons; semicolons", 
                "target/branch-name", false, Instant.now(), Instant.now());

        assertEquals("Hook-Name_123", hook.name());
        assertEquals("Description with @mentions and #tags", hook.description());
        assertEquals("feature/JIRA-123", hook.branchPattern());
        assertEquals("Prompt with $variables and {placeholders}", hook.prompt());
        assertTrue(hook.ruleNames().contains("rule-1"));
        assertTrue(hook.ruleNames().contains("rule_2"));
        assertEquals("Rules with: colons; semicolons", hook.extraRules());
        assertEquals("target/branch-name", hook.targetBranch());
    }

    @Test
    void constructorWithEmptyRulesList() {
        AutomationHook hook = new AutomationHook(1L, "Empty Rules Hook", "No rules", 
                true, "PR_EVENT", "opened", "main", null, "REVIEW", "Prompt", 
                List.of(), null, "main", false, Instant.now(), Instant.now());

        assertNotNull(hook.ruleNames());
        assertTrue(hook.ruleNames().isEmpty());
        assertEquals(0, hook.ruleNames().size());
    }
}