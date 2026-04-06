package com.eneve.agent.agent;

import com.eneve.agent.agent.model.CommentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommentContextTest {

    @Test
    void constructorCreatesCorrectRecord() {
        String prId = "PR-123";
        String organization = "myorg";
        String project = "myproject";
        String repository = "myrepo";
        String filePath = "src/main/java/Example.java";
        int line = 25;
        String category = "code-quality";
        String severity = "high";
        String findingText = "Potential null pointer dereference";
        String reviewJobId = "review-job-456";

        CommentContext context = new CommentContext(prId, organization, project, 
                repository, filePath, line, category, severity, findingText, reviewJobId);

        assertEquals(prId, context.prId());
        assertEquals(organization, context.organization());
        assertEquals(project, context.project());
        assertEquals(repository, context.repository());
        assertEquals(filePath, context.filePath());
        assertEquals(line, context.line());
        assertEquals(category, context.category());
        assertEquals(severity, context.severity());
        assertEquals(findingText, context.findingText());
        assertEquals(reviewJobId, context.reviewJobId());
    }

    @Test
    void constructorWithNullValues() {
        CommentContext context = new CommentContext(null, null, null, null, 
                null, 0, null, null, null, null);

        assertNull(context.prId());
        assertNull(context.organization());
        assertNull(context.project());
        assertNull(context.repository());
        assertNull(context.filePath());
        assertEquals(0, context.line());
        assertNull(context.category());
        assertNull(context.severity());
        assertNull(context.findingText());
        assertNull(context.reviewJobId());
    }

    @Test
    void constructorWithEmptyStrings() {
        CommentContext context = new CommentContext("", "", "", "", "", 
                1, "", "", "", "");

        assertEquals("", context.prId());
        assertEquals("", context.organization());
        assertEquals("", context.project());
        assertEquals("", context.repository());
        assertEquals("", context.filePath());
        assertEquals(1, context.line());
        assertEquals("", context.category());
        assertEquals("", context.severity());
        assertEquals("", context.findingText());
        assertEquals("", context.reviewJobId());
    }

    @Test
    void constructorWithVariousLineNumbers() {
        CommentContext context1 = new CommentContext("PR-1", "org", "proj", "repo", 
                "file.java", 1, "cat", "low", "finding", "job-1");
        CommentContext context2 = new CommentContext("PR-2", "org", "proj", "repo", 
                "file.java", 1000, "cat", "high", "finding", "job-2");
        CommentContext context3 = new CommentContext("PR-3", "org", "proj", "repo", 
                "file.java", -1, "cat", "medium", "finding", "job-3");

        assertEquals(1, context1.line());
        assertEquals(1000, context2.line());
        assertEquals(-1, context3.line());
    }

    @Test
    void constructorWithDifferentCategories() {
        String[] categories = {"security", "performance", "maintainability", 
                "reliability", "code-smell", "bug"};

        for (String category : categories) {
            CommentContext context = new CommentContext("PR-1", "org", "proj", "repo", 
                    "file.java", 10, category, "high", "finding", "job-1");
            assertEquals(category, context.category());
        }
    }

    @Test
    void constructorWithDifferentSeverities() {
        String[] severities = {"low", "medium", "high", "critical", "info", "warning"};

        for (String severity : severities) {
            CommentContext context = new CommentContext("PR-1", "org", "proj", "repo", 
                    "file.java", 10, "security", severity, "finding", "job-1");
            assertEquals(severity, context.severity());
        }
    }

    @Test
    void constructorWithDifferentFileTypes() {
        String[] filePaths = {
            "src/main/java/Example.java",
            "src/test/java/ExampleTest.java",
            "pom.xml",
            "README.md",
            "Dockerfile",
            "package.json",
            "src/main/resources/application.properties",
            "scripts/deploy.sh"
        };

        for (String filePath : filePaths) {
            CommentContext context = new CommentContext("PR-1", "org", "proj", "repo", 
                    filePath, 10, "quality", "medium", "finding", "job-1");
            assertEquals(filePath, context.filePath());
        }
    }

    @Test
    void constructorWithLongFindingText() {
        String longFindingText = "This is a very long finding text that describes " +
                "a complex issue in the code that spans multiple lines and provides " +
                "detailed explanation of the problem and potential solutions that " +
                "should be considered by the developer when reviewing this comment.";

        CommentContext context = new CommentContext("PR-1", "org", "proj", "repo", 
                "file.java", 10, "quality", "high", longFindingText, "job-1");

        assertEquals(longFindingText, context.findingText());
    }

    @Test
    void constructorWithSpecialCharactersInStrings() {
        CommentContext context = new CommentContext("PR-123/feature", "org-name", 
                "proj.name", "repo_name", "src/main/java/com/example/Test.java", 
                50, "code-quality", "medium", "Issue with @Override annotation", 
                "job-abc-123");

        assertEquals("PR-123/feature", context.prId());
        assertEquals("org-name", context.organization());
        assertEquals("proj.name", context.project());
        assertEquals("repo_name", context.repository());
        assertEquals("src/main/java/com/example/Test.java", context.filePath());
        assertEquals("Issue with @Override annotation", context.findingText());
        assertEquals("job-abc-123", context.reviewJobId());
    }

    @Test
    void recordEquality() {
        CommentContext context1 = new CommentContext("PR-1", "org", "proj", "repo", 
                "file.java", 10, "security", "high", "finding", "job-1");
        CommentContext context2 = new CommentContext("PR-1", "org", "proj", "repo", 
                "file.java", 10, "security", "high", "finding", "job-1");
        CommentContext context3 = new CommentContext("PR-2", "org", "proj", "repo", 
                "file.java", 10, "security", "high", "finding", "job-1");

        assertEquals(context1, context2);
        assertNotEquals(context1, context3);
        assertEquals(context1.hashCode(), context2.hashCode());
    }

    @Test
    void recordToString() {
        CommentContext context = new CommentContext("PR-123", "myorg", "myproject", 
                "myrepo", "Example.java", 42, "security", "high", "SQL injection risk", "job-789");
        
        String toString = context.toString();
        assertTrue(toString.contains("PR-123"));
        assertTrue(toString.contains("myorg"));
        assertTrue(toString.contains("myproject"));
        assertTrue(toString.contains("myrepo"));
        assertTrue(toString.contains("Example.java"));
        assertTrue(toString.contains("42"));
        assertTrue(toString.contains("security"));
        assertTrue(toString.contains("high"));
        assertTrue(toString.contains("SQL injection risk"));
        assertTrue(toString.contains("job-789"));
    }

    @Test
    void allAccessorsReturnNonNullValues() {
        // Verifies that all accessors return non-null values for a fully-populated record
        CommentContext context = new CommentContext("PR-1", "org", "proj", "repo", 
                "file.java", 10, "quality", "medium", "finding", "job-1");

        assertNotNull(context.prId());
        assertNotNull(context.organization());
        assertNotNull(context.project());
        assertNotNull(context.repository());
        assertNotNull(context.filePath());
        assertNotNull(context.category());
        assertNotNull(context.severity());
        assertNotNull(context.findingText());
        assertNotNull(context.reviewJobId());
    }

    @Test
    void constructorWithBitbucketContext() {
        // Test with Bitbucket-style context (no project, empty string)
        CommentContext context = new CommentContext("PR-456", "myorg", "", "myrepo", 
                "src/main/Application.java", 15, "performance", "medium", 
                "Inefficient loop detected", "review-job-789");

        assertEquals("", context.project());
        assertEquals("myorg", context.organization());
    }

    @Test
    void constructorWithAzureDevOpsContext() {
        // Test with Azure DevOps-style context (with project)
        CommentContext context = new CommentContext("123", "myorg", "myproject", "myrepo", 
                "Controllers/HomeController.cs", 30, "security", "critical", 
                "Potential XSS vulnerability", "review-job-abc");

        assertEquals("myproject", context.project());
        assertEquals("Controllers/HomeController.cs", context.filePath());
    }
}
