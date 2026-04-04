package com.eneve.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobTypeTest {

    @Test
    void enumHasExpectedValues() {
        JobType[] values = JobType.values();
        
        assertEquals(23, values.length);
        assertEquals(JobType.FIX,                        values[0]);
        assertEquals(JobType.REVIEW,                     values[1]);
        assertEquals(JobType.FIX_PR,                     values[2]);
        assertEquals(JobType.REPLY,                      values[3]);
        assertEquals(JobType.FIX_COMMENT,                values[4]);
        assertEquals(JobType.HOOK,                       values[5]);
        assertEquals(JobType.GENERATE_TESTS,             values[6]);
        assertEquals(JobType.GENERATE_DOCS,              values[7]);
        assertEquals(JobType.SYNC_CONFLUENCE,            values[8]);
        assertEquals(JobType.METRICS,                    values[9]);
        assertEquals(JobType.QUALITY_REPORT,             values[10]);
        assertEquals(JobType.REVIEW_EPIC,                values[11]);
        assertEquals(JobType.REVIEW_FEATURE,             values[12]);
        assertEquals(JobType.REVIEW_USERSTORY,           values[13]);
        assertEquals(JobType.CHAT,                       values[14]);
        assertEquals(JobType.PROMOTE,                    values[15]);
        assertEquals(JobType.SELF_ANALYSIS,              values[16]);
        assertEquals(JobType.GENERATE_ARCHITECTURE,      values[17]);
        assertEquals(JobType.GENERATE_CLOUD_ARCHITECTURE,values[18]);
        assertEquals(JobType.KNOWLEDGE_GRAPH,            values[19]);
        assertEquals(JobType.TECH_DEBT,                  values[20]);
        assertEquals(JobType.REWRITE,                    values[21]);
        assertEquals(JobType.SERVICE_DESK_TRIAGE,        values[22]);
    }

    @Test
    void enumValuesHaveCorrectNames() {
        assertEquals("FIX",              JobType.FIX.name());
        assertEquals("REVIEW",           JobType.REVIEW.name());
        assertEquals("FIX_PR",           JobType.FIX_PR.name());
        assertEquals("REPLY",            JobType.REPLY.name());
        assertEquals("FIX_COMMENT",      JobType.FIX_COMMENT.name());
        assertEquals("HOOK",             JobType.HOOK.name());
        assertEquals("GENERATE_TESTS",   JobType.GENERATE_TESTS.name());
        assertEquals("GENERATE_DOCS",    JobType.GENERATE_DOCS.name());
        assertEquals("SYNC_CONFLUENCE",  JobType.SYNC_CONFLUENCE.name());
        assertEquals("METRICS",          JobType.METRICS.name());
        assertEquals("QUALITY_REPORT",   JobType.QUALITY_REPORT.name());
        assertEquals("REVIEW_EPIC",      JobType.REVIEW_EPIC.name());
        assertEquals("REVIEW_FEATURE",   JobType.REVIEW_FEATURE.name());
        assertEquals("REVIEW_USERSTORY", JobType.REVIEW_USERSTORY.name());
        assertEquals("CHAT",             JobType.CHAT.name());
        assertEquals("PROMOTE",                    JobType.PROMOTE.name());
        assertEquals("SELF_ANALYSIS",              JobType.SELF_ANALYSIS.name());
        assertEquals("GENERATE_ARCHITECTURE",      JobType.GENERATE_ARCHITECTURE.name());
        assertEquals("GENERATE_CLOUD_ARCHITECTURE",JobType.GENERATE_CLOUD_ARCHITECTURE.name());
        assertEquals("KNOWLEDGE_GRAPH",            JobType.KNOWLEDGE_GRAPH.name());
        assertEquals("TECH_DEBT",                  JobType.TECH_DEBT.name());
        assertEquals("REWRITE",                    JobType.REWRITE.name());
        assertEquals("SERVICE_DESK_TRIAGE",        JobType.SERVICE_DESK_TRIAGE.name());
    }

    @Test
    void enumValueOfWorks() {
        assertEquals(JobType.FIX,              JobType.valueOf("FIX"));
        assertEquals(JobType.REVIEW,           JobType.valueOf("REVIEW"));
        assertEquals(JobType.FIX_PR,           JobType.valueOf("FIX_PR"));
        assertEquals(JobType.REPLY,            JobType.valueOf("REPLY"));
        assertEquals(JobType.FIX_COMMENT,      JobType.valueOf("FIX_COMMENT"));
        assertEquals(JobType.HOOK,             JobType.valueOf("HOOK"));
        assertEquals(JobType.GENERATE_TESTS,   JobType.valueOf("GENERATE_TESTS"));
        assertEquals(JobType.GENERATE_DOCS,    JobType.valueOf("GENERATE_DOCS"));
        assertEquals(JobType.SYNC_CONFLUENCE,  JobType.valueOf("SYNC_CONFLUENCE"));
        assertEquals(JobType.METRICS,          JobType.valueOf("METRICS"));
        assertEquals(JobType.QUALITY_REPORT,   JobType.valueOf("QUALITY_REPORT"));
        assertEquals(JobType.REVIEW_EPIC,      JobType.valueOf("REVIEW_EPIC"));
        assertEquals(JobType.REVIEW_FEATURE,   JobType.valueOf("REVIEW_FEATURE"));
        assertEquals(JobType.REVIEW_USERSTORY, JobType.valueOf("REVIEW_USERSTORY"));
        assertEquals(JobType.CHAT,             JobType.valueOf("CHAT"));
        assertEquals(JobType.PROMOTE,                    JobType.valueOf("PROMOTE"));
        assertEquals(JobType.SELF_ANALYSIS,              JobType.valueOf("SELF_ANALYSIS"));
        assertEquals(JobType.GENERATE_ARCHITECTURE,      JobType.valueOf("GENERATE_ARCHITECTURE"));
        assertEquals(JobType.GENERATE_CLOUD_ARCHITECTURE,JobType.valueOf("GENERATE_CLOUD_ARCHITECTURE"));
        assertEquals(JobType.KNOWLEDGE_GRAPH,            JobType.valueOf("KNOWLEDGE_GRAPH"));
        assertEquals(JobType.TECH_DEBT,                  JobType.valueOf("TECH_DEBT"));
        assertEquals(JobType.REWRITE,                    JobType.valueOf("REWRITE"));
        assertEquals(JobType.SERVICE_DESK_TRIAGE,        JobType.valueOf("SERVICE_DESK_TRIAGE"));
    }

    @Test
    void enumValueOfThrowsExceptionForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> JobType.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> JobType.valueOf("fix")); // case sensitive
        assertThrows(IllegalArgumentException.class, () -> JobType.valueOf(""));
    }

    @Test
    void enumValueOfThrowsExceptionForNull() {
        assertThrows(NullPointerException.class, () -> JobType.valueOf(null));
    }

    @Test
    void enumOrdinals() {
        assertEquals(0,  JobType.FIX.ordinal());
        assertEquals(1,  JobType.REVIEW.ordinal());
        assertEquals(2,  JobType.FIX_PR.ordinal());
        assertEquals(3,  JobType.REPLY.ordinal());
        assertEquals(4,  JobType.FIX_COMMENT.ordinal());
        assertEquals(5,  JobType.HOOK.ordinal());
        assertEquals(6,  JobType.GENERATE_TESTS.ordinal());
        assertEquals(7,  JobType.GENERATE_DOCS.ordinal());
        assertEquals(8,  JobType.SYNC_CONFLUENCE.ordinal());
        assertEquals(9,  JobType.METRICS.ordinal());
        assertEquals(10, JobType.QUALITY_REPORT.ordinal());
        assertEquals(11, JobType.REVIEW_EPIC.ordinal());
        assertEquals(12, JobType.REVIEW_FEATURE.ordinal());
        assertEquals(13, JobType.REVIEW_USERSTORY.ordinal());
        assertEquals(14, JobType.CHAT.ordinal());
        assertEquals(15, JobType.PROMOTE.ordinal());
        assertEquals(16, JobType.SELF_ANALYSIS.ordinal());
        assertEquals(17, JobType.GENERATE_ARCHITECTURE.ordinal());
        assertEquals(18, JobType.GENERATE_CLOUD_ARCHITECTURE.ordinal());
        assertEquals(19, JobType.KNOWLEDGE_GRAPH.ordinal());
        assertEquals(20, JobType.TECH_DEBT.ordinal());
        assertEquals(21, JobType.REWRITE.ordinal());
        assertEquals(22, JobType.SERVICE_DESK_TRIAGE.ordinal());
    }

    @Test
    void enumEquality() {
        assertEquals(JobType.FIX, JobType.FIX);
        assertNotEquals(JobType.FIX, JobType.REVIEW);
        assertNotEquals(JobType.HOOK, JobType.REPLY);
    }

    @Test
    void enumToString() {
        assertEquals("FIX", JobType.FIX.toString());
        assertEquals("REVIEW", JobType.REVIEW.toString());
        assertEquals("FIX_COMMENT", JobType.FIX_COMMENT.toString());
    }
}