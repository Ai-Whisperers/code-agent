package com.eneve.agent.scm;

import java.util.List;
import java.util.Map;

/**
 * Platform-agnostic interface for git hosting operations (PRs, comments).
 * <p>
 * Parameter mapping per platform:
 * <ul>
 *   <li>Bitbucket Cloud: org = workspace, project = "" (ignored), repo = repoSlug</li>
 *   <li>Azure DevOps:    org = organization, project = project name, repo = repository name</li>
 * </ul>
 */
public interface GitPlatformService {

    /**
     * Create a pull request.
     * @return two-element array: [prUrl, prId]
     */
    String[] createPullRequest(String org, String project, String repo,
                               String sourceBranch, String targetBranch,
                               String title, String description);

    void mergePullRequest(String org, String project, String repo, String prId);

    void declinePullRequest(String org, String project, String repo, String prId);

    /**
     * Fetch pull request metadata.
     * @return map with keys: sourceBranch, destinationBranch, title
     */
    Map<String, String> getPullRequestInfo(String org, String project, String repo, String prId);

    /**
     * Add a general comment to a pull request.
     * @return the platform comment ID
     */
    long addPrComment(String org, String project, String repo, String prId, String body);

    /**
     * Update the body of an existing general comment (used to edit the review summary in-place).
     */
    void updatePrComment(String org, String project, String repo, String prId,
                         long commentId, String body);

    /**
     * Add an inline comment on a specific file and line in a pull request.
     * @return the platform comment ID
     */
    long addInlinePrComment(String org, String project, String repo, String prId,
                            String filePath, int line, String body);

    /**
     * Post a threaded reply to an existing comment.
     * @return the platform comment ID of the reply
     */
    long replyToComment(String org, String project, String repo, String prId,
                        long parentCommentId, String body);

    /**
     * Fetch all comments in the thread rooted at the given comment ID.
     */
    List<ThreadComment> getCommentThread(String org, String project, String repo,
                                         String prId, long rootCommentId);

    /**
     * Fetch all review comments from a pull request, excluding the agent's own comments.
     */
    List<String> getPullRequestComments(String org, String project, String repo, String prId);

    /**
     * Fetch all comments authored by the agent on a pull request.
     */
    List<AgentComment> getAgentPrComments(String org, String project, String repo, String prId);

    /**
     * Resolve (close) a comment thread on a pull request.
     * Platform-specific: Bitbucket sets comment state to "resolved",
     * GitLab resolves the discussion, Azure DevOps sets the thread status to "Fixed".
     */
    void resolveComment(String org, String project, String repo, String prId, long commentId);

    /**
     * Upload a binary file to platform-specific file hosting and return its public URL.
     * Used to host rendered diagram images that can be embedded in PR comments.
     * <p>
     * For Bitbucket Cloud this uploads to the repository Downloads section.
     * Implementations for platforms that do not support file uploads return {@code null}.
     *
     * @param org         workspace / organization
     * @param repo        repository slug / name
     * @param filename    target filename (re-uploading the same name replaces the file)
     * @param data        raw file bytes
     * @param contentType MIME type of the file (e.g. {@code "image/png"})
     * @return public URL of the uploaded file, or {@code null} if not supported
     */
    default String uploadDownload(String org, String repo, String filename,
                                  byte[] data, String contentType) {
        return null;
    }

    /**
     * List all repository slugs / names within an org or workspace.
     * <p>
     * Implementations that do not support workspace-level repository listing
     * (e.g. Azure DevOps, which requires a project segment) should return an
     * empty list. {@link com.eneve.agent.agent.RepoSyncService} skips sync
     * gracefully when the list is empty.
     *
     * @param org workspace, namespace, or organisation slug
     * @return mutable list of repository slugs/names; empty if not supported
     */
    default List<String> listRepositories(String org) {
        return List.of();
    }

    /**
     * Builds an authenticated HTTPS clone URL for the given repository.
     *
     * <p>Used by scheduled jobs (code-graph builder, upgrade checker) that need to clone
     * a repository identified only by its {@code workspace} and {@code repoSlug}, without
     * a full clone URL being available in advance.
     *
     * <p>Implementations that cannot construct a valid clone URL from these two parameters
     * alone (e.g. Azure DevOps, which also requires a {@code project} segment) must return
     * {@code null}. Callers are expected to skip such repos gracefully.
     *
     * @param workspace workspace, namespace, or organisation slug
     * @param repoSlug  repository slug or name
     * @return authenticated HTTPS clone URL, or {@code null} if not supported
     */
    default String buildCloneUrl(String workspace, String repoSlug) {
        return null;
    }
}
