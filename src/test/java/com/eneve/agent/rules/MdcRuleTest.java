package com.eneve.agent.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MdcRuleTest {

    @Test
    void recordCreationAndAccessors() {
        List<String> globs = List.of("*.java", "*.ts");
        MdcRule rule = new MdcRule(
            "test.mdc",
            "A test rule",
            globs,
            true,
            "Rule body content"
        );
        
        assertEquals("test.mdc", rule.fileName());
        assertEquals("A test rule", rule.description());
        assertEquals(globs, rule.globs());
        assertTrue(rule.alwaysApply());
        assertEquals("Rule body content", rule.body());
    }

    @Test
    void recordWithEmptyValues() {
        MdcRule rule = new MdcRule("", "", List.of(), false, "");
        
        assertEquals("", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        assertEquals("", rule.body());
    }

    @Test
    void recordWithNullValues() {
        MdcRule rule = new MdcRule(null, null, null, false, null);
        
        assertNull(rule.fileName());
        assertNull(rule.description());
        assertNull(rule.globs());
        assertFalse(rule.alwaysApply());
        assertNull(rule.body());
    }

    @Test
    void recordEquality() {
        List<String> globs = List.of("*.java");
        
        MdcRule rule1 = new MdcRule("file.mdc", "desc", globs, true, "body");
        MdcRule rule2 = new MdcRule("file.mdc", "desc", globs, true, "body");
        MdcRule rule3 = new MdcRule("file.mdc", "desc", globs, false, "body");
        
        assertEquals(rule1, rule2);
        assertNotEquals(rule1, rule3);
        assertEquals(rule1.hashCode(), rule2.hashCode());
    }

    @Test
    void recordToString() {
        MdcRule rule = new MdcRule("test.mdc", "Test description", List.of("*.js"), true, "Body");
        String toString = rule.toString();
        
        assertTrue(toString.contains("test.mdc"));
        assertTrue(toString.contains("Test description"));
        assertTrue(toString.contains("*.js"));
        assertTrue(toString.contains("true"));
        assertTrue(toString.contains("Body"));
    }

    @Test
    void recordWithSingletonGlobList() {
        MdcRule rule = new MdcRule("rule.mdc", "Single glob", List.of("*.py"), false, "Python rule");
        
        assertEquals(1, rule.globs().size());
        assertEquals("*.py", rule.globs().get(0));
    }

    @Test
    void recordWithMultipleGlobs() {
        List<String> multipleGlobs = List.of("*.java", "*.kt", "*.scala", "build.gradle");
        MdcRule rule = new MdcRule("jvm.mdc", "JVM languages", multipleGlobs, true, "JVM rule body");
        
        assertEquals(4, rule.globs().size());
        assertTrue(rule.globs().contains("*.java"));
        assertTrue(rule.globs().contains("*.kt"));
        assertTrue(rule.globs().contains("*.scala"));
        assertTrue(rule.globs().contains("build.gradle"));
    }

    @Test
    void recordWithLongBody() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longBody.append("This is line ").append(i).append("\n");
        }
        
        MdcRule rule = new MdcRule("long.mdc", "Long rule", List.of(), false, longBody.toString());
        
        assertEquals(longBody.toString(), rule.body());
        assertTrue(rule.body().length() > 10000);
    }
}