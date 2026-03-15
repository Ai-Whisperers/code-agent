package com.eneve.agent.agent;

import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic structural tests for {@link MermaidPngRenderer}.
 */
class MermaidPngRendererTest {

    @Test
    void isAnnotatedApplicationScoped() {
        assertNotNull(MermaidPngRenderer.class.getAnnotation(ApplicationScoped.class),
                "MermaidPngRenderer must be @ApplicationScoped for CDI injection");
    }

    @Test
    void canBeInstantiated() {
        assertDoesNotThrow(MermaidPngRenderer::new,
                "MermaidPngRenderer should have a no-arg constructor for CDI proxying");
    }

    @Test
    void renderToPng_invalidMermaidSyntax_throwsWithMessage() {
        MermaidPngRenderer renderer = new MermaidPngRenderer();
        Exception ex = assertThrows(Exception.class,
                () -> renderer.renderToPng("this is not valid mermaid !@#$%^"),
                "renderToPng should throw when mmdc fails or produces no output");
        assertNotNull(ex.getMessage());
    }
}
