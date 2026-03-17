package com.eneve.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuardrailConfigTest {

    @Test
    void configClassHasCorrectGetterMethods() {
        // Test that the GuardrailConfig class has the expected methods
        // We can't easily test the actual configuration values without a full Quarkus context,
        // so we'll test the basic structure
        
        GuardrailConfig config = new GuardrailConfig();
        
        // Verify methods exist and don't throw exceptions when called
        assertDoesNotThrow(() -> {
            config.getBlockedPaths();
            config.getAllowedCommands();
            config.getMaxFilesChanged();
            config.getMaxLinesChanged();
            config.getMaxLoopIterations();
        });
    }

    @Test
    void configClassHasCorrectReturnTypes() {
        GuardrailConfig config = new GuardrailConfig();
        
        // Test that getters return the expected types (even if null/0 due to no config)
        Object blockedPaths = config.getBlockedPaths();
        Object allowedCommands = config.getAllowedCommands();
        int maxFiles = config.getMaxFilesChanged();
        int maxLines = config.getMaxLinesChanged();
        int maxIterations = config.getMaxLoopIterations();
        
        // blockedPaths and allowedCommands could be null if not configured
        assertTrue(blockedPaths == null || blockedPaths instanceof List<?>);
        assertTrue(allowedCommands == null || allowedCommands instanceof List<?>);
        
        // Numeric values should be non-negative
        assertTrue(maxFiles >= 0);
        assertTrue(maxLines >= 0);
        assertTrue(maxIterations >= 0);
    }

    @Test
    void configClassIsApplicationScoped() {
        // Verify the class has the correct annotation structure
        Class<GuardrailConfig> configClass = GuardrailConfig.class;
        
        // Check that it's annotated with ApplicationScoped
        assertTrue(configClass.isAnnotationPresent(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test
    void configFieldsHaveConfigPropertyAnnotations() throws NoSuchFieldException {
        // Verify that the fields have the expected annotations
        Class<GuardrailConfig> configClass = GuardrailConfig.class;
        
        // Check that the class has fields with ConfigProperty annotations
        assertTrue(configClass.getDeclaredField("blockedPaths")
            .isAnnotationPresent(org.eclipse.microprofile.config.inject.ConfigProperty.class));
        assertTrue(configClass.getDeclaredField("allowedCommands")
            .isAnnotationPresent(org.eclipse.microprofile.config.inject.ConfigProperty.class));
        assertTrue(configClass.getDeclaredField("maxFilesChanged")
            .isAnnotationPresent(org.eclipse.microprofile.config.inject.ConfigProperty.class));
        assertTrue(configClass.getDeclaredField("maxLinesChanged")
            .isAnnotationPresent(org.eclipse.microprofile.config.inject.ConfigProperty.class));
        assertTrue(configClass.getDeclaredField("maxLoopIterations")
            .isAnnotationPresent(org.eclipse.microprofile.config.inject.ConfigProperty.class));
    }

    @Test
    void defaultValuesAreDefinedInAnnotations() throws NoSuchFieldException {
        Class<GuardrailConfig> configClass = GuardrailConfig.class;
        
        // Check that default values are defined for numeric fields
        org.eclipse.microprofile.config.inject.ConfigProperty maxFilesAnnotation = 
            configClass.getDeclaredField("maxFilesChanged")
                .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class);
        assertEquals("10", maxFilesAnnotation.defaultValue());
        
        org.eclipse.microprofile.config.inject.ConfigProperty maxLinesAnnotation = 
            configClass.getDeclaredField("maxLinesChanged")
                .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class);
        assertEquals("500", maxLinesAnnotation.defaultValue());
        
        org.eclipse.microprofile.config.inject.ConfigProperty maxIterationsAnnotation = 
            configClass.getDeclaredField("maxLoopIterations")
                .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class);
        assertEquals("50", maxIterationsAnnotation.defaultValue());
    }
}