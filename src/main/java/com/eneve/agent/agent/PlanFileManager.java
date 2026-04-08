package com.eneve.agent.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import com.eneve.agent.planner.ExecutionPlan;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles creation and management of markdown files for execution plans.
 */
@ApplicationScoped
public class PlanFileManager {

    private static final Logger LOG = Logger.getLogger(PlanFileManager.class);

    @Inject
    PlanWorkspaceManager planWorkspaceManager;

    /**
     * Creates a physical .md file in the plan's workspace directory.
     * 
     * @param planId the execution plan ID
     * @param markdownContent the content to write to the file
     * @return the absolute path to the created file, or null if creation failed
     */
    public String createPlanMarkdownFile(String planId, String markdownContent) {
        try {
            // Acquire or create workspace for this plan
            WorkspaceContext workspace = planWorkspaceManager.acquire(planId);
            
            // Create the markdown file path
            String filename = "plan-" + planId.substring(0, 8) + ".md";
            Path filePath = workspace.getRoot().resolve(filename);
            
            // Write the markdown content to the file
            Files.writeString(filePath, markdownContent, StandardCharsets.UTF_8);
            
            LOG.infof("Created plan markdown file: %s", filePath);
            return filePath.toString();
            
        } catch (Exception e) {
            LOG.errorf("Failed to create markdown file for plan %s: %s", planId, e.getMessage());
            return null;
        }
    }

    /**
     * Generates formatted markdown content for a plan.
     * 
     * @param plan the execution plan
     * @param userRequest the original user request that triggered the plan
     * @return formatted markdown content
     */
    public String generatePlanMarkdown(ExecutionPlan plan, String userRequest) {
        StringBuilder md = new StringBuilder();
        
        md.append("# Execution Plan: ").append(plan.title()).append("\n\n");
        md.append("**Plan ID:** `").append(plan.planId()).append("`\n");
        md.append("**Status:** ").append(plan.status()).append("\n");
        md.append("**Created:** ").append(plan.createdAt()).append("\n\n");
        
        md.append("## Original Request\n\n");
        md.append("```\n").append(userRequest).append("\n```\n\n");
        
        md.append("## Repository Information\n\n");
        md.append("- **Repository:** ").append(plan.repoUrl() != null ? plan.repoUrl() : "Not specified").append("\n");
        md.append("- **Target Branch:** ").append(plan.targetBranch()).append("\n\n");
        
        if (plan.planData() != null && plan.planData().phases() != null) {
            md.append("## Implementation Plan\n\n");
            for (int i = 0; i < plan.planData().phases().size(); i++) {
                var phase = plan.planData().phases().get(i);
                md.append("### Phase ").append(i + 1).append(": ").append(getPhaseTitle(phase)).append("\n\n");
                
                if (phase.steps() != null) {
                    for (int j = 0; j < phase.steps().size(); j++) {
                        var step = phase.steps().get(j);
                        md.append("- **Step ").append(j + 1).append(":** ").append(step.title()).append("\n");
                        String description = getStepDescription(step);
                        if (description != null && !description.isEmpty()) {
                            md.append("  - ").append(description).append("\n");
                        }
                    }
                    md.append("\n");
                }
            }
        }
        
        md.append("## Notes\n\n");
        md.append("- This plan was generated from a chat conversation\n");
        md.append("- Edit this file as needed to refine the implementation approach\n");
        md.append("- Click the **Implement ⚡️** button to start execution\n");
        
        return md.toString();
    }

    /**
     * Safely extracts phase title, handling potential method name variations.
     */
    private String getPhaseTitle(Object phase) {
        try {
            // Try different possible method names for phase title
            var method = phase.getClass().getMethod("title");
            return (String) method.invoke(phase);
        } catch (Exception e) {
            // Fallback to string representation
            return phase.toString();
        }
    }

    /**
     * Safely extracts step description, handling potential method name variations.
     */
    private String getStepDescription(Object step) {
        try {
            var method = step.getClass().getMethod("description");
            return (String) method.invoke(step);
        } catch (Exception e) {
            return null;
        }
    }
}
