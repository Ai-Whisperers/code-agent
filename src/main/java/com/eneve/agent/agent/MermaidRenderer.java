package com.eneve.agent.agent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.eneve.agent.settings.SettingsService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Renders Mermaid diagram syntax into the appropriate Markdown format for the
 * configured git platform.
 *
 * GitHub, GitLab, and Azure DevOps render Mermaid fenced code blocks natively.
 * Bitbucket Cloud does not, so diagrams are converted to mermaid.ink image URLs
 * which render as embedded images in any Markdown viewer.
 */
@ApplicationScoped
public class MermaidRenderer {

    @Inject SettingsService settings;

    /** Overrides the settings lookup when set directly (e.g. in tests). */
    String platform;

    public String platform() {
        if (platform != null) return platform;
        return settings.get("git.platform", "bitbucket");
    }

    /**
     * Returns the Markdown representation of a Mermaid diagram appropriate for
     * the current platform.
     *
     * @param title       alt text / summary title for the diagram
     * @param mermaidSyntax raw Mermaid diagram source (without fences)
     * @return Markdown string ready to embed in a PR comment
     */
    public String render(String title, String mermaidSyntax) {
        if ("bitbucket".equalsIgnoreCase(platform().trim())) {
            String encoded = Base64.getEncoder()
                    .encodeToString(mermaidSyntax.getBytes(StandardCharsets.UTF_8));
            return "![" + title + "](https://mermaid.ink/img/base64:" + encoded + ")";
        }
        return "```mermaid\n" + mermaidSyntax + "\n```";
    }
}
