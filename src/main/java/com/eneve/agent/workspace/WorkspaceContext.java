package com.eneve.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * Manages an isolated workspace directory for a single agent job.
 * Handles cloning the repo, path resolution with traversal protection, and cleanup.
 *
 * <p>Plan-managed workspaces ({@link #createPlanManaged}) survive across multiple job steps:
 * {@link #close()} is a no-op so the try-with-resources in each handler does not delete the
 * directory. The owning {@code PlanWorkspaceManager} calls {@link #forceClose()} when the
 * plan reaches a terminal state.
 */
public class WorkspaceContext implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WorkspaceContext.class);

    private final Path root;
    private final Map<String, String> metadata = new HashMap<>();
    private final Map<String, Path> clonedRepos = new HashMap<>(); // repoSlug -> subdirectory path
    private boolean planManaged;
    private boolean keepOnClose;
    private String userId;
    private String conversationId;

    private WorkspaceContext(Path root) {
        this.root = root;
    }

    /**
     * Set the user ID for MCP tool credential resolution.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Get the user ID for MCP tool credential resolution.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Set the conversation ID so tools can persist context back to the database.
     */
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * Get the conversation ID, or {@code null} for non-chat workspaces.
     */
    public String getConversationId() {
        return conversationId;
    }

    public static WorkspaceContext create(String jobId) throws IOException {
        Path tmp = Files.createTempDirectory("agent-job-" + jobId + "-");
        LOG.infof("Created workspace: %s", tmp);
        return new WorkspaceContext(tmp);
    }

    /**
     * Reuses an existing workspace directory from a previous (failed) job.
     * The directory must exist and already contain a cloned repository.
     * Returns {@code null} if the path is blank, does not exist, or has no {@code .git} directory.
     */
    public static WorkspaceContext reuse(String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) return null;
        Path root = Path.of(workspacePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            LOG.warnf("Cannot reuse workspace — directory does not exist: %s", workspacePath);
            return null;
        }
        if (!Files.exists(root.resolve(".git"))) {
            LOG.warnf("Cannot reuse workspace — no .git directory found: %s", workspacePath);
            return null;
        }
        LOG.infof("Reusing preserved workspace: %s", root);
        return new WorkspaceContext(root);
    }

    /**
     * Creates a workspace whose lifecycle is managed by {@code PlanWorkspaceManager}.
     * {@link #close()} is a no-op; use {@link #forceClose()} to actually delete it.
     */
    public static WorkspaceContext createPlanManaged(String planId) throws IOException {
        Path tmp = Files.createTempDirectory("agent-plan-" + planId.substring(0, 8) + "-");
        LOG.infof("Created plan-managed workspace: %s (plan %s)", tmp, planId);
        WorkspaceContext ws = new WorkspaceContext(tmp);
        ws.planManaged = true;
        return ws;
    }

    /**
     * Returns {@code true} if a git repository has already been cloned into this workspace
     * (i.e. {@code .git} directory exists at the root).
     */
    public boolean hasClonedRepo() {
        return Files.exists(root.resolve(".git")) || !clonedRepos.isEmpty();
    }

    /**
     * Returns {@code true} if the specified repository slug has been cloned into a subdirectory.
     */
    public boolean hasClonedRepo(String repoSlug) {
        if (repoSlug == null || repoSlug.isBlank()) {
            return hasClonedRepo();
        }
        return clonedRepos.containsKey(repoSlug);
    }

    /**
     * Returns the path to the specified repository subdirectory, or null if not cloned.
     */
    public Path getRepoPath(String repoSlug) {
        return clonedRepos.get(repoSlug);
    }

    /**
     * Returns the set of cloned repository slugs.
     */
    public Set<String> listClonedRepos() {
        return Set.copyOf(clonedRepos.keySet());
    }

    public Path getRoot() {
        return root;
    }

    public void putMetadata(String key, String value) {
        metadata.put(key, value);
    }

    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Clone the repo into this workspace.
     * Uses authenticated HTTPS URL for Bitbucket Cloud.
     */
    public void cloneRepo(String authenticatedUrl, String branchName, long timeoutMinutes)
            throws IOException, InterruptedException {

        runGit(timeoutMinutes, "clone", "--depth", "50", "--branch", branchName, authenticatedUrl, ".");
        LOG.infof("Cloned repo into %s on branch %s", root, branchName);
    }

    /**
     * Shallow clone (depth 1) suitable for read-only operations such as metrics scanning.
     * Fetches only the latest tree, which is significantly faster for large repos with
     * long histories compared to the standard depth-50 clone.
     */
    public void cloneRepoShallow(String authenticatedUrl, String branchName, long timeoutMinutes)
            throws IOException, InterruptedException {

        runGit(timeoutMinutes, "clone", "--depth", "1", "--branch", branchName, authenticatedUrl, ".");
        LOG.infof("Shallow-cloned repo into %s on branch %s", root, branchName);
    }

    /**
     * Clone, then create and checkout a new branch.
     * Useful when the branch doesn't exist on the remote yet.
     */
    public void cloneAndCreateBranch(String authenticatedUrl, String baseBranch, String newBranch,
                                     long timeoutMinutes) throws IOException, InterruptedException {
        runGit(timeoutMinutes, "clone", "--depth", "50", "--branch", baseBranch, authenticatedUrl, ".");
        runGit(timeoutMinutes, "checkout", "-b", newBranch);
        LOG.infof("Cloned repo on %s and created branch %s", baseBranch, newBranch);
    }

    /**
     * Clone a repository into a subdirectory for multi-repo workspace support.
     * Creates the subdirectory if it doesn't exist.
     */
    public void cloneRepoToSubdir(String repoSlug, String authenticatedUrl, String branchName, long timeoutMinutes)
            throws IOException, InterruptedException {
        
        if (repoSlug == null || repoSlug.isBlank()) {
            throw new IllegalArgumentException("repoSlug cannot be null or blank");
        }
        
        Path repoDir = root.resolve(repoSlug);
        if (Files.exists(repoDir)) {
            if (clonedRepos.containsKey(repoSlug)) {
                LOG.infof("Repository %s already cloned in %s", repoSlug, repoDir);
                return;
            }
            throw new IOException("Directory " + repoDir + " already exists but is not tracked as a cloned repo");
        }
        
        Files.createDirectories(repoDir);
        
        // Clone into the subdirectory
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "50", "--branch", branchName, authenticatedUrl, ".")
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
        
        Process proc = pb.start();
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Git clone timed out after " + timeoutMinutes + " minutes");
        }
        
        if (proc.exitValue() != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IOException("Git clone failed (exit " + proc.exitValue() + "): " + output);
        }
        
        clonedRepos.put(repoSlug, repoDir);
        LOG.infof("Cloned repository %s into %s on branch %s", repoSlug, repoDir, branchName);
    }

    /**
     * Shallow clone a repository into a subdirectory for read-only operations.
     */
    public void cloneRepoToSubdirShallow(String repoSlug, String authenticatedUrl, String branchName, long timeoutMinutes)
            throws IOException, InterruptedException {
        
        if (repoSlug == null || repoSlug.isBlank()) {
            throw new IllegalArgumentException("repoSlug cannot be null or blank");
        }
        
        Path repoDir = root.resolve(repoSlug);
        if (Files.exists(repoDir)) {
            if (clonedRepos.containsKey(repoSlug)) {
                LOG.infof("Repository %s already cloned in %s", repoSlug, repoDir);
                return;
            }
            throw new IOException("Directory " + repoDir + " already exists but is not tracked as a cloned repo");
        }
        
        Files.createDirectories(repoDir);
        
        // Shallow clone into the subdirectory
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", "--branch", branchName, authenticatedUrl, ".")
                .directory(repoDir.toFile())
                .redirectErrorStream(true);
        
        Process proc = pb.start();
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Git clone timed out after " + timeoutMinutes + " minutes");
        }
        
        if (proc.exitValue() != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IOException("Git clone failed (exit " + proc.exitValue() + "): " + output);
        }
        
        clonedRepos.put(repoSlug, repoDir);
        LOG.infof("Shallow-cloned repository %s into %s on branch %s", repoSlug, repoDir, branchName);
    }

    /**
     * Configure git user identity for commits in this workspace.
     * Required for Repository Access Tokens where the author must match the bot email.
     */
    public void configureAuthor(String name, String email) throws IOException, InterruptedException {
        runGit(1, "config", "user.name", name);
        runGit(1, "config", "user.email", email);
        LOG.infof("Configured git author: %s <%s>", name, email);
    }

    /**
     * Stage and commit all changes. Returns true if a commit was made, false if working tree was clean.
     */
    public boolean commitAll(String message) throws IOException, InterruptedException {
        runGit(5, "add", "-A");
        String status = runGitOutput(1, "status", "--porcelain");
        if (status.isBlank()) {
            LOG.info("Nothing to commit — working tree clean");
            return false;
        }
        runGit(5, "commit", "-m", message);
        return true;
    }

    public void createBranch(String branchName) throws IOException, InterruptedException {
        runGit(1, "checkout", "-b", branchName);
        LOG.infof("Created and checked out branch %s", branchName);
    }

    public void push(String branchName, long timeoutMinutes) throws IOException, InterruptedException {
        runGit(timeoutMinutes, "push", "origin", branchName);
    }

    /**
     * Cherry-pick a list of commit SHAs onto the current branch in order.
     * Uses {@code --allow-empty} so identical fixup commits do not abort the promotion.
     *
     * @throws IOException if cherry-pick fails (conflict or other error)
     */
    public void cherryPick(java.util.List<String> commitShas, long timeoutMinutes)
            throws IOException, InterruptedException {
        for (String sha : commitShas) {
            runGit(timeoutMinutes, "cherry-pick", "--allow-empty", sha);
            LOG.infof("Cherry-picked %s onto current branch", sha);
        }
    }

    /**
     * Pull with rebase to incorporate remote changes before pushing.
     * Used when committing directly to a shared branch to avoid push rejections.
     */
    public void pullRebase(String branchName, long timeoutMinutes) throws IOException, InterruptedException {
        runGit(timeoutMinutes, "pull", "--rebase", "origin", branchName);
    }

    /**
     * Fetch a remote branch so it is available as origin/{branch} for diff operations.
     */
    public void fetchBranch(String branchName, long timeoutMinutes) throws IOException, InterruptedException {
        runGit(timeoutMinutes, "fetch", "origin", branchName);
        LOG.infof("Fetched origin/%s", branchName);
    }

    /**
     * Get the full unified diff between the current HEAD and the merge base with the target branch.
     * Equivalent to {@code git diff origin/<targetBranch>...HEAD}.
     */
    public String getDiff(String targetBranch) throws IOException, InterruptedException {
        return runGitOutput(2, "diff", "origin/" + targetBranch + "...HEAD");
    }

    /**
     * Get the unified diff between a specific commit and the current HEAD.
     * Used for incremental reviews where we only want changes since the last reviewed commit.
     */
    public String getDiffFromCommit(String commitSha) throws IOException, InterruptedException {
        return runGitOutput(2, "diff", commitSha + "...HEAD");
    }

    /**
     * Get the full unified diff of all uncommitted changes in the working tree.
     * Stages everything first (git add -A) then returns the staged diff.
     * Used by the self-review step to show the agent what it has changed before committing.
     */
    public String getWorkingDiff() throws IOException, InterruptedException {
        runGit(1, "add", "-A");
        return runGitOutput(2, "diff", "--cached");
    }

    /**
     * Get the full SHA of the current HEAD commit.
     */
    public String getHeadSha() throws IOException, InterruptedException {
        return runGitOutput(1, "rev-parse", "HEAD").trim();
    }

    /**
     * Returns true if there are any commits reachable from HEAD that are not reachable
     * from {@code sinceRef}. Use the SHA captured before the agent loop to detect
     * whether the agent committed anything.
     */
    public boolean hasCommitsSince(String sinceRef) throws IOException, InterruptedException {
        String output = runGitOutput(1, "log", sinceRef + "..HEAD", "--oneline");
        return !output.isBlank();
    }

    /**
     * Stages all changes and returns the unified diff of everything staged.
     * Returns an empty string if the working tree is clean.
     */
    public String stageAndGetDiff() throws IOException, InterruptedException {
        runGit(1, "add", "-A");
        return runGitOutput(2, "diff", "--cached");
    }

    /**
     * Check whether a given object (commit SHA) exists in the repository.
     */
    public boolean objectExists(String sha) {
        try {
            runGit(1, "cat-file", "-t", sha);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String diffStat() throws IOException, InterruptedException {
        return runGitOutput(2, "diff", "--stat", "HEAD~1");
    }

    public int countFilesChanged() throws IOException, InterruptedException {
        String output = runGitOutput(2, "diff", "--name-only", "HEAD~1");
        return (int) output.lines().filter(l -> !l.isBlank()).count();
    }

    /**
     * Returns the relative paths of files changed in the most recent commit,
     * suitable for scoping linter reports to only agent-touched files.
     */
    public java.util.Set<String> getChangedFileNames() throws IOException, InterruptedException {
        String output = runGitOutput(2, "diff", "--name-only", "HEAD~1");
        return output.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public int countLinesChanged() throws IOException, InterruptedException {
        String output = runGitOutput(2, "diff", "--shortstat", "HEAD~1");
        int total = 0;
        for (String part : output.split(",")) {
            String trimmed = part.trim();
            if (trimmed.contains("insertion") || trimmed.contains("deletion")) {
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length > 0) {
                    try {
                        total += Integer.parseInt(tokens[0]);
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return total;
    }

    /**
     * Resolve a relative path and verify it stays within the workspace root.
     * Throws SecurityException on traversal attempt.
     */
    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal blocked: " + relativePath);
        }
        return resolved;
    }

    /**
     * Marks this workspace to be preserved on disk when {@link #close()} is called.
     * Use this before failing a job so a subsequent retry job can reuse the
     * already-cloned repository via {@link #reuse(String)}.
     */
    public void keepOnClose() {
        this.keepOnClose = true;
    }

    /** Returns the absolute path of the workspace root directory. */
    public String getAbsolutePath() {
        return root.toAbsolutePath().toString();
    }

    @Override
    public void close() {
        if (planManaged || keepOnClose) {
            return;
        }
        doClose();
    }

    /**
     * Unconditionally deletes the workspace directory regardless of whether it is
     * plan-managed. Called by {@code PlanWorkspaceManager} when the plan completes or fails.
     */
    public void forceClose() {
        doClose();
    }

    private void doClose() {
        try {
            if (Files.exists(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                }
                LOG.infof("Cleaned up workspace: %s", root);
            }
        } catch (IOException e) {
            LOG.warnf("Failed to clean up workspace %s: %s", root, e.getMessage());
        }
    }

    private void runGit(long timeoutMinutes, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Git command timed out: " + String.join(" ", cmd));
        }
        if (proc.exitValue() != 0) {
            String output = new String(proc.getInputStream().readAllBytes());
            throw new IOException("Git command failed (exit " + proc.exitValue() + "): " + output);
        }
    }

    private String runGitOutput(long timeoutMinutes, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("Git command timed out: " + String.join(" ", cmd));
        }
        return output;
    }
}
