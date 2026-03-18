package com.eneve.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunFixRequestTest {

    @Test
    void shouldSkipPrCreationReturnsFalseWhenNull() {
        RunFixRequest req = new RunFixRequest(
                "https://bitbucket.org/org/repo.git", "agent/branch", null,
                "fix prompt", "develop", null, null, null, null, null, null);
        assertFalse(req.shouldSkipPrCreation());
    }

    @Test
    void shouldSkipPrCreationReturnsFalseWhenFalse() {
        RunFixRequest req = new RunFixRequest(
                "https://bitbucket.org/org/repo.git", "agent/branch", null,
                "fix prompt", "develop", null, null, null, null, null, Boolean.FALSE);
        assertFalse(req.shouldSkipPrCreation());
    }

    @Test
    void shouldSkipPrCreationReturnsTrueWhenTrue() {
        RunFixRequest req = new RunFixRequest(
                "https://bitbucket.org/org/repo.git", "agent/branch", null,
                "fix prompt", "develop", null, null, null, null, null, Boolean.TRUE);
        assertTrue(req.shouldSkipPrCreation());
    }

    @Test
    void targetBranchOrDefaultReturnsDevelopWhenSet() {
        RunFixRequest req = new RunFixRequest(
                "https://bitbucket.org/org/repo.git", "agent/branch", null,
                null, "develop", null, null, null, null, null, null);
        assertEquals("develop", req.targetBranchOrDefault());
    }

    @Test
    void targetBranchOrDefaultReturnsMainWhenNull() {
        RunFixRequest req = new RunFixRequest(
                "https://bitbucket.org/org/repo.git", "agent/branch", null,
                null, null, null, null, null, null, null, null);
        assertEquals("main", req.targetBranchOrDefault());
    }
}
