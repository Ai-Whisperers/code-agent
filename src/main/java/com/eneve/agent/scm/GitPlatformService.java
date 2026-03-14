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
}
