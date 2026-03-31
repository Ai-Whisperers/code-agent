package com.eneve.agent.tools;

import com.eneve.agent.agent.store.ScopeItemProposalStore;
import com.eneve.agent.model.ScopeProposal;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Executes the {@code update_proposal} tool on behalf of the Product Owner AI chat.
 *
 * <p>Authorization gate: only runs when the workspace has been tagged with
 * {@code scopeImproveChat=true} by {@code ScopeImproveChatService}, preventing
 * this write tool from being invoked in any other agent context.
 */
@ApplicationScoped
public class UpdateProposalToolExecutor implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(UpdateProposalToolExecutor.class);

    @Inject ScopeItemProposalStore proposalStore;
    @Inject ObjectMapper mapper;

    @Override
    public String name() {
        return "update_proposal";
    }

    @Override
    public boolean isAuthorized(WorkspaceContext workspace) {
        return workspace != null && "true".equals(workspace.getMetadata("scopeImproveChat"));
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String proposalId = (String) input.get("proposal_id");
        if (proposalId == null || proposalId.isBlank()) {
            return "ERROR: proposal_id is required";
        }

        String summary     = strOrNull(input, "proposed_summary");
        String description = strOrNull(input, "proposed_description");
        String criteria    = strOrNull(input, "proposed_criteria");
        String technical   = strOrNull(input, "proposed_technical");
        String label       = strOrNull(input, "proposed_label");
        String priority    = strOrNull(input, "proposed_priority");

        try {
            proposalStore.updateFields(proposalId, summary, description, criteria, technical, label, priority);
            ScopeProposal updated = proposalStore.findById(proposalId).orElse(null);
            if (updated == null) {
                return "ERROR: proposal not found after update: " + proposalId;
            }
            String json = mapper.writeValueAsString(updated);
            LOG.infof("UpdateProposalToolExecutor: updated proposal %s", proposalId);
            return json;
        } catch (Exception e) {
            LOG.errorf("UpdateProposalToolExecutor: failed to update %s: %s", proposalId, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    private static String strOrNull(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }
}
