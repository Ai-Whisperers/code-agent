package com.eneve.agent.agent.service;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.scm.bitbucket.BitbucketBranchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Standardises the branch topology of Bitbucket repositories:
 * <ol>
 *   <li>If {@code master} exists → create {@code main} at its HEAD, set {@code main} as the
 *       default branch, then delete {@code master}.</li>
 *   <li>Else if {@code main} exists but is not yet the default → set it as the default.</li>
 *   <li>If {@code develop} does not exist → create it from the current HEAD of {@code main}.</li>
 * </ol>
 *
 * <p>Every step is idempotent; re-running the same repo is always safe.
 * The service is a no-op when {@code git.platform} is not {@code bitbucket}.
 */
@ApplicationScoped
public class BranchStandardizationService {

    private static final Logger LOG = Logger.getLogger(BranchStandardizationService.class);

    static final String MAIN    = "main";
    static final String MASTER  = "master";
    static final String DEVELOP = "develop";

    @Inject
    BitbucketBranchService branchService;

    @Inject
    RepoSettingsStore settingsStore;

    @ConfigProperty(name = "git.platform", defaultValue = "bitbucket")
    String gitPlatform;

    /**
     * Runs standardization for every non-archived repo in {@code repo_settings}.
     *
     * @return one {@link RepoResult} per repo processed
     */
    public List<RepoResult> standardizeAll() {
        if (!isBitbucket()) {
            return List.of();
        }
        List<RepoResult> results = new ArrayList<>();
        for (RepoSettings repo : settingsStore.listAll()) {
            if (repo.archived()) {
                continue;
            }
            results.add(standardizeRepo(repo.workspace(), repo.repoSlug()));
        }
        return results;
    }

    /**
     * Runs standardization for a single repository.
     *
     * @return a {@link RepoResult} describing every action taken (or skipped)
     */
    public RepoResult standardizeRepo(String workspace, String repoSlug) {
        List<String> actions = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors  = new ArrayList<>();

        if (!isBitbucket()) {
            skipped.add("git.platform is not bitbucket");
            return new RepoResult(workspace, repoSlug, actions, skipped, errors);
        }

        try {
            ensureMain(workspace, repoSlug, actions, skipped, errors);
            ensureDevelop(workspace, repoSlug, actions, skipped, errors);
        } catch (Exception e) {
            errors.add("Unexpected error: " + e.getMessage());
            LOG.warnf("Branch standardization failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }

        return new RepoResult(workspace, repoSlug, actions, skipped, errors);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Step 1 — ensure {@code main} is the default branch.
     * <ul>
     *   <li>If {@code master} exists: create {@code main} at master's HEAD,
     *       set default, delete {@code master}.</li>
     *   <li>If only {@code main} exists: ensure it is set as default.</li>
     * </ul>
     */
    private void ensureMain(String workspace, String repo,
                            List<String> actions, List<String> skipped, List<String> errors) {
        Optional<String> masterHash = branchService.getBranchHash(workspace, repo, MASTER);
        Optional<String> mainHash   = branchService.getBranchHash(workspace, repo, MAIN);
        Optional<String> defaultBranch = branchService.getDefaultBranch(workspace, repo);

        if (masterHash.isPresent()) {
            // Create main if it doesn't exist yet
            if (mainHash.isEmpty()) {
                try {
                    branchService.createBranch(workspace, repo, MAIN, masterHash.get());
                    actions.add("created branch 'main' from 'master' (" + masterHash.get().substring(0, 8) + ")");
                } catch (Exception e) {
                    errors.add("Failed to create 'main' from 'master': " + e.getMessage());
                    return;
                }
            } else {
                skipped.add("'main' already exists — skipping create");
            }

            // Set main as default
            if (!MAIN.equals(defaultBranch.orElse(""))) {
                try {
                    branchService.setDefaultBranch(workspace, repo, MAIN);
                    actions.add("set default branch to 'main'");
                } catch (Exception e) {
                    errors.add("Failed to set default branch to 'main': " + e.getMessage());
                    return;
                }
            } else {
                skipped.add("'main' is already the default branch");
            }

            // Delete master
            try {
                branchService.deleteBranch(workspace, repo, MASTER);
                actions.add("deleted branch 'master'");
            } catch (Exception e) {
                errors.add("Failed to delete 'master': " + e.getMessage());
            }

        } else if (mainHash.isPresent()) {
            if (!MAIN.equals(defaultBranch.orElse(""))) {
                try {
                    branchService.setDefaultBranch(workspace, repo, MAIN);
                    actions.add("set default branch to 'main'");
                } catch (Exception e) {
                    errors.add("Failed to set default branch to 'main': " + e.getMessage());
                }
            } else {
                skipped.add("'main' already exists and is the default branch");
            }
        } else {
            errors.add("Neither 'master' nor 'main' branch found — cannot standardize");
        }
    }

    /**
     * Step 2 — ensure {@code develop} exists, branching from {@code main}.
     */
    private void ensureDevelop(String workspace, String repo,
                               List<String> actions, List<String> skipped, List<String> errors) {
        Optional<String> developHash = branchService.getBranchHash(workspace, repo, DEVELOP);
        if (developHash.isPresent()) {
            skipped.add("'develop' already exists");
            return;
        }

        Optional<String> mainHash = branchService.getBranchHash(workspace, repo, MAIN);
        if (mainHash.isEmpty()) {
            errors.add("Cannot create 'develop': 'main' branch not found");
            return;
        }

        try {
            branchService.createBranch(workspace, repo, DEVELOP, mainHash.get());
            actions.add("created branch 'develop' from 'main' (" + mainHash.get().substring(0, 8) + ")");
        } catch (Exception e) {
            errors.add("Failed to create 'develop': " + e.getMessage());
        }
    }

    private boolean isBitbucket() {
        return "bitbucket".equalsIgnoreCase(gitPlatform);
    }

    // ── Result model ──────────────────────────────────────────────────────────

    /**
     * Describes the outcome of standardizing a single repository.
     *
     * @param workspace  Bitbucket workspace slug
     * @param repoSlug   Repository slug
     * @param actions    Changes that were successfully applied
     * @param skipped    Steps skipped because the state was already correct
     * @param errors     Steps that failed (non-fatal; other repos still run)
     */
    public record RepoResult(
            String workspace,
            String repoSlug,
            List<String> actions,
            List<String> skipped,
            List<String> errors
    ) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workspace", workspace);
            m.put("repoSlug", repoSlug);
            m.put("actions", actions);
            m.put("skipped", skipped);
            m.put("errors", errors);
            return m;
        }
    }
}
