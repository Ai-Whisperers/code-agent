package com.eneve.agent.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MdcParserTest {

    @Test
    void parseReturnsEmptyRuleForNullContent() {
        MdcRule rule = MdcParser.parse("test.mdc", null);
        
        assertEquals("test.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        assertEquals("", rule.body());
    }

    @Test
    void parseReturnsEmptyRuleForBlankContent() {
        MdcRule rule = MdcParser.parse("test.mdc", "   \n  \t  ");
        
        assertEquals("test.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        assertEquals("", rule.body());
    }

    @Test
    void parseHandlesPlainTextWithoutFrontmatter() {
        String content = "This is just plain text content\nwithout any frontmatter.";
        MdcRule rule = MdcParser.parse("plain.mdc", content);
        
        assertEquals("plain.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertTrue(rule.alwaysApply()); // No frontmatter means always apply
        assertEquals(content, rule.body());
    }

    @Test
    void parseHandlesValidYamlFrontmatterWithAllFields() {
        String content = """
            ---
            description: Test description
            alwaysApply: true
            globs:
              - "*.java"
              - "*.kt"
            ---
            This is the body content.
            Multiple lines here.
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals("test.mdc", rule.fileName());
        assertEquals("Test description", rule.description());
        assertEquals(List.of("*.java", "*.kt"), rule.globs());
        assertTrue(rule.alwaysApply());
        assertEquals("This is the body content.\nMultiple lines here.", rule.body());
    }

    @Test
    void parseHandlesPartialYamlFrontmatter() {
        String content = """
            ---
            description: Only description
            ---
            Body content here.
            """;
        
        MdcRule rule = MdcParser.parse("partial.mdc", content);
        
        assertEquals("partial.mdc", rule.fileName());
        assertEquals("Only description", rule.description());
        assertEquals(List.of(), rule.globs()); // Default empty list
        assertFalse(rule.alwaysApply()); // Default false
        assertEquals("Body content here.", rule.body());
    }

    @Test
    void parseHandlesBooleanStringValues() {
        String content = """
            ---
            alwaysApply: "true"
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertTrue(rule.alwaysApply());
    }

    @Test
    void parseHandlesBooleanStringValuesFalse() {
        String content = """
            ---
            alwaysApply: "false"
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertFalse(rule.alwaysApply());
    }

    @Test
    void parseHandlesBooleanStringValuesCaseInsensitive() {
        String content = """
            ---
            alwaysApply: "TRUE"
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertTrue(rule.alwaysApply());
    }

    @Test
    void parseHandlesStringGlobValue() {
        String content = """
            ---
            globs: "*.java,*.kt,*.scala"
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals(List.of("*.java", "*.kt", "*.scala"), rule.globs());
    }

    @Test
    void parseHandlesSingleStringGlobValue() {
        String content = """
            ---
            globs: "*.java"
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals(List.of("*.java"), rule.globs());
    }

    @Test
    void parseHandlesEmptyStringGlobValue() {
        String content = """
            ---
            globs: ""
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals(List.of(), rule.globs());
    }

    @Test
    void parseHandlesWhitespaceInCommaSeparatedGlobs() {
        String content = """
            ---
            globs: " *.java , *.kt  ,*.scala "
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals(List.of("*.java", "*.kt", "*.scala"), rule.globs());
    }

    @Test
    void parseHandlesMissingSecondDelimiter() {
        String content = """
            ---
            description: Missing closing delimiter
            This should be treated as body
            """;
        
        MdcRule rule = MdcParser.parse("test.mdc", content);
        
        assertEquals("test.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertTrue(rule.alwaysApply()); // Falls back to treating entire content as body
    }

    @Test
    void parseHandlesMalformedYaml() {
        String content = """
            ---
            description: valid
            malformed: [unclosed list
            invalid: yaml: content: here
            ---
            Body content
            """;
        
        MdcRule rule = MdcParser.parse("malformed.mdc", content);
        
        assertEquals("malformed.mdc", rule.fileName());
        assertEquals("", rule.description()); // Falls back to defaults when YAML parsing fails
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        // The entire content should be treated as body when YAML parsing fails
    }

    @Test
    void parseHandlesEmptyFrontmatter() {
        String content = """
            ---
            ---
            Just body content
            """;
        
        MdcRule rule = MdcParser.parse("empty-fm.mdc", content);
        
        assertEquals("empty-fm.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        assertEquals("Just body content", rule.body());
    }

    @Test
    void parseHandlesEmptyBody() {
        String content = """
            ---
            description: Has frontmatter but no body
            ---
            """;
        
        MdcRule rule = MdcParser.parse("no-body.mdc", content);
        
        assertEquals("no-body.mdc", rule.fileName());
        assertEquals("Has frontmatter but no body", rule.description());
        assertEquals("", rule.body());
    }

    @Test
    void parseTrimsWhitespace() {
        String content = """
            
            ---
            description: "  Trimmed description  "
            ---
            
            Body with leading and trailing whitespace
            
            """;
        
        MdcRule rule = MdcParser.parse("trim.mdc", content);
        
        assertEquals("  Trimmed description  ", rule.description()); // YAML values are not trimmed
        assertEquals("Body with leading and trailing whitespace", rule.body()); // Body is trimmed
    }

    @Test
    void parseHandlesNullYamlFrontmatter() {
        String content = """
            ---
            # Just a comment, no actual content
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("null-yaml.mdc", content);
        
        assertEquals("null-yaml.mdc", rule.fileName());
        assertEquals("", rule.description());
        assertEquals(List.of(), rule.globs());
        assertFalse(rule.alwaysApply());
        assertEquals("Body", rule.body());
    }

    @Test
    void parseHandlesComplexYamlTypes() {
        String content = """
            ---
            description: 123
            alwaysApply: false
            globs:
              - pattern1
              - pattern2
            extraField: ignored
            ---
            Body
            """;
        
        MdcRule rule = MdcParser.parse("complex.mdc", content);
        
        assertEquals("123", rule.description()); // Numbers converted to string
        assertFalse(rule.alwaysApply());
        assertEquals(List.of("pattern1", "pattern2"), rule.globs());
        assertEquals("Body", rule.body());
    }
}