package com.eneve.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.eneve.agent.workspace.WorkspaceContext;

/**
 * Integration tests that demonstrate the SearchCodeTool functionality
 * with real file system operations and actual grep searches.
 */
class SearchCodeToolIntegrationTest {

    private SearchCodeTool searchCodeTool;

    @BeforeEach
    void setUp() {
        searchCodeTool = new SearchCodeTool();
        searchCodeTool.gitUsername = "test-user";
        searchCodeTool.gitPassword = "test-token";
    }

    @Test
    void testSearchInJavaCodebase() throws IOException {
        WorkspaceContext workspace = WorkspaceContext.create("integration-test");
        try {
            // Create a mock Java project structure
            setupMockJavaProject(workspace);

            // Search for Java classes
            Map<String, Object> input = Map.of(
                "pattern", "class",
                "include", "*.java"
            );

            String result = searchCodeTool.execute(workspace, input);

            assertNotNull(result);
            assertTrue(result.contains("Found") || result.contains("No matches"));
            
            if (result.contains("Found")) {
                // Should find the Java files but not other files
                assertTrue(result.contains("UserService.java") || result.contains("OrderController.java"));
                // Should not contain non-Java files due to include filter
                assertFalse(result.contains("README.md"));
                assertFalse(result.contains("pom.xml"));
            }

        } finally {
            workspace.forceClose();
        }
    }

    @Test
    void testSearchWithPathScope() throws IOException {
        WorkspaceContext workspace = WorkspaceContext.create("integration-test");
        try {
            setupMockJavaProject(workspace);

            // Search only in src directory
            Map<String, Object> input = Map.of(
                "pattern", "public",
                "path", "src"
            );

            String result = searchCodeTool.execute(workspace, input);

            assertNotNull(result);
            assertFalse(result.startsWith("ERROR:"));
            
            // If matches found, they should be from src directory only
            if (result.contains("Found")) {
                assertTrue(result.contains("src/") || result.contains("UserService") || result.contains("OrderController"));
            }

        } finally {
            workspace.forceClose();
        }
    }

    @Test
    void testSearchWithRegexPattern() throws IOException {
        WorkspaceContext workspace = WorkspaceContext.create("integration-test");
        try {
            setupMockJavaProject(workspace);

            // Search for method patterns
            Map<String, Object> input = Map.of(
                "pattern", "public.*get.*"
            );

            String result = searchCodeTool.execute(workspace, input);

            assertNotNull(result);
            assertFalse(result.startsWith("ERROR:"));
            
        } finally {
            workspace.forceClose();
        }
    }

    @Test
    void testSearchWithRepoParameterFails() throws IOException {
        WorkspaceContext workspace = WorkspaceContext.create("integration-test");
        try {
            // Test with an invalid repository URL
            Map<String, Object> input = Map.of(
                "pattern", "test",
                "repo", "https://github.com/nonexistent/repo"
            );

            String result = searchCodeTool.execute(workspace, input);

            assertTrue(result.startsWith("ERROR: Failed to clone repository"));

        } finally {
            workspace.forceClose();
        }
    }

    private void setupMockJavaProject(WorkspaceContext workspace) throws IOException {
        Path root = workspace.getRoot();
        
        // Create .git directory to simulate a cloned repository
        Files.createDirectory(root.resolve(".git"));
        
        // Create source directory structure
        Path srcDir = root.resolve("src");
        Path serviceDir = srcDir.resolve("main").resolve("java").resolve("com").resolve("example");
        Files.createDirectories(serviceDir);
        
        // Create Java files
        Files.writeString(serviceDir.resolve("UserService.java"), 
            "package com.example;\n\n" +
            "public class UserService {\n" +
            "    public String getUserName(Long id) {\n" +
            "        return \"User \" + id;\n" +
            "    }\n" +
            "}\n"
        );
        
        Files.writeString(serviceDir.resolve("OrderController.java"),
            "package com.example;\n\n" +
            "public class OrderController {\n" +
            "    private UserService userService;\n" +
            "    \n" +
            "    public void processOrder() {\n" +
            "        // Implementation here\n" +
            "    }\n" +
            "}\n"
        );
        
        // Create non-Java files
        Files.writeString(root.resolve("README.md"), "# Sample Project\n\nThis is a test project");
        Files.writeString(root.resolve("pom.xml"), 
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project>\n" +
            "    <groupId>com.example</groupId>\n" +
            "    <artifactId>sample-project</artifactId>\n" +
            "</project>\n"
        );
    }
}