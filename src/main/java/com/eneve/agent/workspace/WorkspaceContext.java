package com.eneve.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * Manages an isolated workspace directory for a single agent job.
 * Handles cloning the repo, path resolution with traversal protection, and cleanup.
 */
public class WorkspaceContext implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(WorkspaceContext.class);

    private final Path root;
    private final Map<String, String> metadata = new HashMap<>();

    private WorkspaceContext(Path root) {
        this.root = root;
    }

    public static WorkspaceContext create(String jobId) throws IOException {
        Path tmp = Files.createTempDirectory("agent-job-" + jobId + "-");
        LOG.infof("Created workspace: %s", tmp);
        return new WorkspaceContext(tmp);
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
     * Get the full SHA of the current HEAD commit.
     */
    public String getHeadSha() throws IOException, InterruptedException {
        return runGitOutput(1, "rev-parse", "HEAD").trim();
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

    @Override
    public void close() {
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
