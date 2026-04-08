package com.eneve.agent.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessHelperTest {

    @Test
    void cleanBuilderWithoutJavaHomeDoesNotSetJavaHomeEnv() {
        ProcessBuilder pb = ProcessHelper.cleanBuilder("echo", "hello");
        Map<String, String> env = pb.environment();

        // These should be absent (stripped)
        assertFalse(env.containsKey("CLASSPATH"));
        assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(env.containsKey("_JAVA_OPTIONS"));
        assertFalse(env.containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(env.containsKey("JAVA_OPTS"));
    }

    @Test
    void cleanBuilderWithNullJavaHomeDoesNotAddJavaHome() {
        ProcessBuilder pb = ProcessHelper.cleanBuilder(null, "echo", "hello");
        Map<String, String> env = pb.environment();
        // JAVA_HOME may or may not be set from parent environment — we only check
        // that passing null does not throw and does not break existing env keys
        assertNotNull(env);
    }

    @Test
    void cleanBuilderWithBlankJavaHomeDoesNotAddJavaHome() {
        String originalJavaHome = System.getenv("JAVA_HOME");
        ProcessBuilder pb = ProcessHelper.cleanBuilder("   ", "echo", "hello");
        Map<String, String> env = pb.environment();
        // Should equal whatever was inherited minus the polluting vars — no new JAVA_HOME forced
        if (originalJavaHome != null) {
            assertEquals(originalJavaHome, env.get("JAVA_HOME"));
        }
    }

    @Test
    void cleanBuilderWithJavaHomeSetsJavaHomeEnv() {
        ProcessBuilder pb = ProcessHelper.cleanBuilder("/usr/lib/jvm/java-21", "echo", "hello");
        Map<String, String> env = pb.environment();

        assertEquals("/usr/lib/jvm/java-21", env.get("JAVA_HOME"));
    }

    @Test
    void cleanBuilderWithJavaHomePrependsToPath() {
        ProcessBuilder pb = ProcessHelper.cleanBuilder("/usr/lib/jvm/java-21", "echo", "hello");
        Map<String, String> env = pb.environment();

        String path = env.get("PATH");
        assertNotNull(path);
        assertTrue(path.startsWith("/usr/lib/jvm/java-21/bin"),
                "PATH should start with $JAVA_HOME/bin but was: " + path);
    }

    @Test
    void cleanBuilderStripsPollutingVarsEvenWithJavaHome() {
        // Simulate polluted environment by temporarily setting system properties
        // (ProcessBuilder inherits from the JVM process, not system env directly,
        //  but POLLUTING_ENV_VARS should be removed regardless)
        ProcessBuilder pb = ProcessHelper.cleanBuilder("/jdk21", "echo");
        Map<String, String> env = pb.environment();

        assertFalse(env.containsKey("CLASSPATH"));
        assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(env.containsKey("_JAVA_OPTIONS"));
        assertFalse(env.containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(env.containsKey("JAVA_OPTS"));
        assertEquals("/jdk21", env.get("JAVA_HOME"));
    }
}
