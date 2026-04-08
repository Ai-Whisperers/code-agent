package com.eneve.agent.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffLineTest {

    @Test
    void typeEnumHasExpectedValues() {
        DiffLine.Type[] types = DiffLine.Type.values();
        
        assertEquals(3, types.length);
        assertEquals(DiffLine.Type.CONTEXT, types[0]);
        assertEquals(DiffLine.Type.ADDED, types[1]);
        assertEquals(DiffLine.Type.REMOVED, types[2]);
    }

    @Test
    void typeEnumValuesHaveCorrectNames() {
        assertEquals("CONTEXT", DiffLine.Type.CONTEXT.name());
        assertEquals("ADDED", DiffLine.Type.ADDED.name());
        assertEquals("REMOVED", DiffLine.Type.REMOVED.name());
    }

    @Test
    void typeEnumValueOfWorks() {
        assertEquals(DiffLine.Type.CONTEXT, DiffLine.Type.valueOf("CONTEXT"));
        assertEquals(DiffLine.Type.ADDED, DiffLine.Type.valueOf("ADDED"));
        assertEquals(DiffLine.Type.REMOVED, DiffLine.Type.valueOf("REMOVED"));
    }

    @Test
    void typeEnumValueOfThrowsExceptionForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> DiffLine.Type.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> DiffLine.Type.valueOf("context")); // case sensitive
    }

    @Test
    void typeEnumOrdinals() {
        assertEquals(0, DiffLine.Type.CONTEXT.ordinal());
        assertEquals(1, DiffLine.Type.ADDED.ordinal());
        assertEquals(2, DiffLine.Type.REMOVED.ordinal());
    }

    @Test
    void recordCreationAndAccessors() {
        DiffLine line = new DiffLine(DiffLine.Type.ADDED, 42, "    public void test() {");
        
        assertEquals(DiffLine.Type.ADDED, line.type());
        assertEquals(42, line.newLineNo());
        assertEquals("    public void test() {", line.content());
    }

    @Test
    void recordWithContextType() {
        DiffLine line = new DiffLine(DiffLine.Type.CONTEXT, 10, "unchanged line");
        
        assertEquals(DiffLine.Type.CONTEXT, line.type());
        assertEquals(10, line.newLineNo());
        assertEquals("unchanged line", line.content());
    }

    @Test
    void recordWithRemovedType() {
        DiffLine line = new DiffLine(DiffLine.Type.REMOVED, -1, "deleted line");
        
        assertEquals(DiffLine.Type.REMOVED, line.type());
        assertEquals(-1, line.newLineNo());
        assertEquals("deleted line", line.content());
    }

    @Test
    void recordWithNullValues() {
        DiffLine line = new DiffLine(null, 0, null);
        
        assertNull(line.type());
        assertEquals(0, line.newLineNo());
        assertNull(line.content());
    }

    @Test
    void recordWithEmptyContent() {
        DiffLine line = new DiffLine(DiffLine.Type.ADDED, 1, "");
        
        assertEquals(DiffLine.Type.ADDED, line.type());
        assertEquals(1, line.newLineNo());
        assertEquals("", line.content());
    }

    @Test
    void recordWithWhitespaceContent() {
        DiffLine line = new DiffLine(DiffLine.Type.CONTEXT, 5, "   \t   ");
        
        assertEquals(DiffLine.Type.CONTEXT, line.type());
        assertEquals(5, line.newLineNo());
        assertEquals("   \t   ", line.content());
    }

    @Test
    void recordWithLongContent() {
        String longContent = "a".repeat(10000);
        DiffLine line = new DiffLine(DiffLine.Type.REMOVED, 100, longContent);
        
        assertEquals(DiffLine.Type.REMOVED, line.type());
        assertEquals(100, line.newLineNo());
        assertEquals(longContent, line.content());
        assertEquals(10000, line.content().length());
    }

    @Test
    void recordWithSpecialCharacters() {
        String specialContent = "Line with special chars: 🌟 ñ á ü \"quotes\" 'apostrophes' [brackets]";
        DiffLine line = new DiffLine(DiffLine.Type.ADDED, 25, specialContent);
        
        assertEquals(DiffLine.Type.ADDED, line.type());
        assertEquals(25, line.newLineNo());
        assertEquals(specialContent, line.content());
    }

    @Test
    void recordWithNegativeLineNumber() {
        DiffLine line = new DiffLine(DiffLine.Type.REMOVED, -5, "removed content");
        
        assertEquals(DiffLine.Type.REMOVED, line.type());
        assertEquals(-5, line.newLineNo());
        assertEquals("removed content", line.content());
    }

    @Test
    void recordEquality() {
        DiffLine line1 = new DiffLine(DiffLine.Type.ADDED, 10, "content");
        DiffLine line2 = new DiffLine(DiffLine.Type.ADDED, 10, "content");
        DiffLine line3 = new DiffLine(DiffLine.Type.ADDED, 11, "content");
        
        assertEquals(line1, line2);
        assertNotEquals(line1, line3);
        assertEquals(line1.hashCode(), line2.hashCode());
    }

    @Test
    void recordToString() {
        DiffLine line = new DiffLine(DiffLine.Type.CONTEXT, 42, "test content");
        String toString = line.toString();
        
        assertTrue(toString.contains("CONTEXT"));
        assertTrue(toString.contains("42"));
        assertTrue(toString.contains("test content"));
    }
}