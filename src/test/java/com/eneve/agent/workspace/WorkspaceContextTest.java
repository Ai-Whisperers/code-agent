package com.eneve.agent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceContextTest {

    @Test
    void createGeneratesWorkspaceWithJobId() throws IOException {
        String jobId = "test-job-123";
        
        try (WorkspaceContext workspace = WorkspaceContext.create(jobId)) {
            Path root = workspace.getRoot();
            
            assertNotNull(root);
            assertTrue(Files.exists(root));
            assertTrue(Files.isDirectory(root));
            assertTrue(root.toString().contains("agent-job-" + jobId));
        }
    }

    @Test
    void getRootReturnsCorrectPath() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path root = workspace.getRoot();
            
            assertTrue(Files.exists(root));
            assertTrue(Files.isDirectory(root));
        }
    }

    @Test
    void resolveReturnsCorrectPathForValidRelativePath() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve("src/main/java/Test.java");
            
            assertTrue(resolved.startsWith(workspace.getRoot()));
            assertTrue(resolved.toString().endsWith("src/main/java/Test.java".replace('/', java.io.File.separatorChar)));
        }
    }

    @Test
    void resolveThrowsSecurityExceptionForPathTraversal() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            SecurityException exception = assertThrows(SecurityException.class,
                () -> workspace.resolve("../../../etc/passwd"));
            
            assertTrue(exception.getMessage().contains("Path traversal blocked"));
        }
    }

    @Test
    void resolveThrowsSecurityExceptionForAbsolutePath() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            SecurityException exception = assertThrows(SecurityException.class,
                () -> workspace.resolve("/etc/passwd"));
            
            assertTrue(exception.getMessage().contains("Path traversal blocked"));
        }
    }

    @Test
    void resolveThrowsSecurityExceptionForComplexTraversal() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            SecurityException exception = assertThrows(SecurityException.class,
                () -> workspace.resolve("src/../../../../../../etc/passwd"));
            
            assertTrue(exception.getMessage().contains("Path traversal blocked"));
        }
    }

    @Test
    void resolveHandlesCurrentDirectoryReference() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve("./src/Test.java");
            
            assertTrue(resolved.startsWith(workspace.getRoot()));
            assertTrue(resolved.toString().endsWith("src/Test.java".replace('/', java.io.File.separatorChar)));
        }
    }

    @Test
    void resolveHandlesBackReferencesWithinBounds() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve("src/../lib/test.jar");
            
            assertTrue(resolved.startsWith(workspace.getRoot()));
            assertTrue(resolved.toString().endsWith("lib/test.jar".replace('/', java.io.File.separatorChar)));
        }
    }

    @Test
    void resolveNormalizesPath() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve("src//main/../main/java/Test.java");
            
            assertTrue(resolved.startsWith(workspace.getRoot()));
            // Path should be normalized without double slashes and unnecessary "../main" traversal
            String normalizedPath = resolved.toString();
            assertFalse(normalizedPath.contains("//"));
        }
    }

    @Test
    void resolveWorksWithEmptyString() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve("");
            
            assertEquals(workspace.getRoot(), resolved);
        }
    }

    @Test
    void resolveWorksWithDot() throws IOException {
        try (WorkspaceContext workspace = WorkspaceContext.create("test")) {
            Path resolved = workspace.resolve(".");
            
            assertEquals(workspace.getRoot(), resolved);
        }
    }

    @Test
    void closeDeletesWorkspaceDirectory(@TempDir Path tempDir) throws Exception {
        // Create workspace context manually to control the cleanup test
        Path workspaceRoot = Files.createTempDirectory(tempDir, "test-workspace");
        
        // Create some files in the workspace
        Files.createDirectory(workspaceRoot.resolve("src"));
        Files.createFile(workspaceRoot.resolve("src/Test.java"));
        Files.createFile(workspaceRoot.resolve("README.md"));
        
        // Verify files exist
        assertTrue(Files.exists(workspaceRoot));
        assertTrue(Files.exists(workspaceRoot.resolve("src/Test.java")));
        assertTrue(Files.exists(workspaceRoot.resolve("README.md")));
        
        // Create and close the workspace context using reflection
        WorkspaceContext workspace = createWorkspaceContext(workspaceRoot);
        workspace.close();
        
        // Verify cleanup happened
        assertFalse(Files.exists(workspaceRoot));
    }

    @Test
    void closeHandlesNonExistentDirectory() throws Exception {
        // Create workspace context with non-existent path using reflection
        Path nonExistent = Path.of("non-existent-path-" + System.currentTimeMillis());
        WorkspaceContext workspace = createWorkspaceContext(nonExistent);
        
        // Should not throw exception
        assertDoesNotThrow(() -> workspace.close());
    }

    @Test
    void multipleWorkspacesHaveUniqueDirectories() throws IOException {
        try (WorkspaceContext workspace1 = WorkspaceContext.create("job1");
             WorkspaceContext workspace2 = WorkspaceContext.create("job2")) {
            
            assertNotEquals(workspace1.getRoot(), workspace2.getRoot());
            assertTrue(Files.exists(workspace1.getRoot()));
            assertTrue(Files.exists(workspace2.getRoot()));
        }
    }

    @Test
    void createPlanManagedCreatesDirectoryWithPlanPrefix() throws IOException {
        String planId = "abcdef12-0000-0000-0000-000000000000";
        WorkspaceContext ws = WorkspaceContext.createPlanManaged(planId);
        try {
            Path root = ws.getRoot();
            assertNotNull(root);
            assertTrue(Files.exists(root));
            assertTrue(root.toString().contains("agent-plan-abcdef12"));
        } finally {
            ws.forceClose();
        }
    }

    @Test
    void closePlanManagedIsNoOp() throws IOException {
        WorkspaceContext ws = WorkspaceContext.createPlanManaged("plan-close-noop-00000000-0000");
        Path root = ws.getRoot();
        assertTrue(Files.exists(root));
        ws.close(); // should NOT delete
        assertTrue(Files.exists(root), "Plan-managed workspace should survive close()");
        ws.forceClose();
        assertFalse(Files.exists(root));
    }

    @Test
    void forceCloseDeletesPlanManagedWorkspace() throws IOException {
        WorkspaceContext ws = WorkspaceContext.createPlanManaged("plan-force-close-00000000");
        Path root = ws.getRoot();
        assertTrue(Files.exists(root));
        ws.forceClose();
        assertFalse(Files.exists(root));
    }

    @Test
    void hasClonedRepoReturnsFalseWhenNoGitDir() throws IOException {
        try (WorkspaceContext ws = WorkspaceContext.create("test-no-git")) {
            assertFalse(ws.hasClonedRepo());
        }
    }

    @Test
    void hasClonedRepoReturnsTrueWhenGitDirPresent() throws IOException {
        try (WorkspaceContext ws = WorkspaceContext.create("test-with-git")) {
            Files.createDirectory(ws.getRoot().resolve(".git"));
            assertTrue(ws.hasClonedRepo());
        }
    }

    // Helper method to create WorkspaceContext via reflection (since constructor is private)
    private WorkspaceContext createWorkspaceContext(Path root) throws Exception {
        Constructor<WorkspaceContext> constructor = WorkspaceContext.class.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(root);
    }
}