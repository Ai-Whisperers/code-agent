package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEntryTest {

    @Test
    void explicitFactoryMethodCreatesCorrectEntry() {
        String workspace = "test-workspace";
        String repoSlug = "test-repo";
        String memoryText = "Prefer composition over inheritance";
        String createdBy = "john.doe";

        Instant before = Instant.now().minusSeconds(1);
        MemoryEntry entry = MemoryEntry.explicit(workspace, repoSlug, memoryText, createdBy);
        Instant after = Instant.now().plusSeconds(1);

        assertNull(entry.id());
        assertEquals(workspace, entry.workspace());
        assertEquals(repoSlug, entry.repoSlug());
        assertEquals(memoryText, entry.memoryText());
        assertNull(entry.category());
        assertEquals("EXPLICIT", entry.source());
        assertNull(entry.sourceCommentId());
        assertNull(entry.sourcePrId());
        assertTrue(entry.isActive());
        assertNotNull(entry.createdAt());
        assertTrue(entry.createdAt().isAfter(before));
        assertTrue(entry.createdAt().isBefore(after));
        assertEquals(createdBy, entry.createdBy());
    }

    @Test
    void extractedFactoryMethodCreatesCorrectEntry() {
        String workspace = "test-workspace";
        String repoSlug = "test-repo";
        String memoryText = "Always validate input parameters";
        String category = "validation";
        Long sourceCommentId = 123L;
        String sourcePrId = "PR-456";
        String createdBy = "jane.doe";

        Instant before = Instant.now().minusSeconds(1);
        MemoryEntry entry = MemoryEntry.extracted(workspace, repoSlug, memoryText, 
                category, sourceCommentId, sourcePrId, createdBy);
        Instant after = Instant.now().plusSeconds(1);

        assertNull(entry.id());
        assertEquals(workspace, entry.workspace());
        assertEquals(repoSlug, entry.repoSlug());
        assertEquals(memoryText, entry.memoryText());
        assertEquals(category, entry.category());
        assertEquals("EXTRACTED", entry.source());
        assertEquals(sourceCommentId, entry.sourceCommentId());
        assertEquals(sourcePrId, entry.sourcePrId());
        assertTrue(entry.isActive());
        assertNotNull(entry.createdAt());
        assertTrue(entry.createdAt().isAfter(before));
        assertTrue(entry.createdAt().isBefore(after));
        assertEquals(createdBy, entry.createdBy());
    }

    @Test
    void explicitFactoryMethodWithNullValues() {
        MemoryEntry entry = MemoryEntry.explicit(null, null, null, null);

        assertNull(entry.id());
        assertNull(entry.workspace());
        assertNull(entry.repoSlug());
        assertNull(entry.memoryText());
        assertNull(entry.category());
        assertEquals("EXPLICIT", entry.source());
        assertNull(entry.sourceCommentId());
        assertNull(entry.sourcePrId());
        assertTrue(entry.isActive());
        assertNotNull(entry.createdAt());
        assertNull(entry.createdBy());
    }

    @Test
    void extractedFactoryMethodWithNullValues() {
        MemoryEntry entry = MemoryEntry.extracted(null, null, null, null, null, null, null);

        assertNull(entry.id());
        assertNull(entry.workspace());
        assertNull(entry.repoSlug());
        assertNull(entry.memoryText());
        assertNull(entry.category());
        assertEquals("EXTRACTED", entry.source());
        assertNull(entry.sourceCommentId());
        assertNull(entry.sourcePrId());
        assertTrue(entry.isActive());
        assertNotNull(entry.createdAt());
        assertNull(entry.createdBy());
    }

    @Test
    void explicitFactoryMethodWithEmptyStrings() {
        MemoryEntry entry = MemoryEntry.explicit("", "", "", "");

        assertEquals("", entry.workspace());
        assertEquals("", entry.repoSlug());
        assertEquals("", entry.memoryText());
        assertEquals("", entry.createdBy());
        assertEquals("EXPLICIT", entry.source());
        assertTrue(entry.isActive());
    }

    @Test
    void extractedFactoryMethodWithEmptyStrings() {
        MemoryEntry entry = MemoryEntry.extracted("", "", "", "", 0L, "", "");

        assertEquals("", entry.workspace());
        assertEquals("", entry.repoSlug());
        assertEquals("", entry.memoryText());
        assertEquals("", entry.category());
        assertEquals("EXTRACTED", entry.source());
        assertEquals(0L, entry.sourceCommentId());
        assertEquals("", entry.sourcePrId());
        assertEquals("", entry.createdBy());
        assertTrue(entry.isActive());
    }

    @Test
    void constructorCreatesCorrectEntry() {
        Long id = 1L;
        String workspace = "workspace";
        String repoSlug = "repo";
        String memoryText = "memory";
        String category = "category";
        String source = "MANUAL";
        Long sourceCommentId = 2L;
        String sourcePrId = "PR-3";
        boolean isActive = false;
        Instant createdAt = Instant.now();
        String createdBy = "user";

        MemoryEntry entry = new MemoryEntry(id, workspace, repoSlug, memoryText, 
                category, source, sourceCommentId, sourcePrId, isActive, createdAt, createdBy);

        assertEquals(id, entry.id());
        assertEquals(workspace, entry.workspace());
        assertEquals(repoSlug, entry.repoSlug());
        assertEquals(memoryText, entry.memoryText());
        assertEquals(category, entry.category());
        assertEquals(source, entry.source());
        assertEquals(sourceCommentId, entry.sourceCommentId());
        assertEquals(sourcePrId, entry.sourcePrId());
        assertEquals(isActive, entry.isActive());
        assertEquals(createdAt, entry.createdAt());
        assertEquals(createdBy, entry.createdBy());
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        MemoryEntry entry1 = new MemoryEntry(1L, "workspace", "repo", "memory", 
                "category", "source", 123L, "PR-1", true, now, "user");
        MemoryEntry entry2 = new MemoryEntry(1L, "workspace", "repo", "memory", 
                "category", "source", 123L, "PR-1", true, now, "user");
        MemoryEntry entry3 = new MemoryEntry(2L, "workspace", "repo", "memory", 
                "category", "source", 123L, "PR-1", true, now, "user");

        assertEquals(entry1, entry2);
        assertNotEquals(entry1, entry3);
        assertEquals(entry1.hashCode(), entry2.hashCode());
    }

    @Test
    void recordToString() {
        MemoryEntry entry = new MemoryEntry(1L, "workspace", "repo", "memory", 
                "category", "source", 123L, "PR-1", true, Instant.now(), "user");
        
        String toString = entry.toString();
        assertTrue(toString.contains("workspace"));
        assertTrue(toString.contains("repo"));
        assertTrue(toString.contains("memory"));
        assertTrue(toString.contains("category"));
        assertTrue(toString.contains("source"));
    }

    @Test
    void explicitEntryHasActiveStatus() {
        MemoryEntry entry = MemoryEntry.explicit("ws", "repo", "text", "user");
        assertTrue(entry.isActive());
    }

    @Test
    void extractedEntryHasActiveStatus() {
        MemoryEntry entry = MemoryEntry.extracted("ws", "repo", "text", "cat", 1L, "PR-1", "user");
        assertTrue(entry.isActive());
    }

    @Test
    void factoryMethodsSetCreatedAtToNow() {
        Instant before = Instant.now().minusSeconds(1);
        
        MemoryEntry explicit = MemoryEntry.explicit("ws", "repo", "text", "user");
        MemoryEntry extracted = MemoryEntry.extracted("ws", "repo", "text", "cat", 1L, "PR-1", "user");
        
        Instant after = Instant.now().plusSeconds(1);
        
        assertTrue(explicit.createdAt().isAfter(before));
        assertTrue(explicit.createdAt().isBefore(after));
        assertTrue(extracted.createdAt().isAfter(before));
        assertTrue(extracted.createdAt().isBefore(after));
    }
}
