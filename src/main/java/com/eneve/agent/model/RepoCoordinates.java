package com.eneve.agent.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts organization, project, and repository from a git hosting platform URL.
 * <p>
 * For Bitbucket Cloud the {@code project} field is empty since Bitbucket uses
 * a flat workspace/repo hierarchy. For Azure DevOps all three fields are populated.
 */
public record RepoCoordinates(String organization, String project, String repository) {

    // ── Bitbucket Cloud ──────────────────────────────────────────────────
    private static final Pattern BB_HTTPS =
            Pattern.compile("https?://(?:[^@]+@)?bitbucket\\.org/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern BB_SSH =
            Pattern.compile("git@bitbucket\\.org:([^/]+)/([^/]+?)(?:\\.git)?$");

    // ── Azure DevOps ─────────────────────────────────────────────────────
    // https://dev.azure.com/{org}/{project}/_git/{repo}
    // https://{org}@dev.azure.com/{org}/{project}/_git/{repo}
    private static final Pattern ADO_HTTPS =
            Pattern.compile("https?://(?:[^@]+@)?dev\\.azure\\.com/([^/]+)/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$");
    // {org}@vs-ssh.visualstudio.com:v3/{org}/{project}/{repo}
    private static final Pattern ADO_SSH =
            Pattern.compile("git@ssh\\.dev\\.azure\\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?$");
    // Legacy: https://{org}.visualstudio.com/{project}/_git/{repo}
    private static final Pattern ADO_LEGACY =
            Pattern.compile("https?://([^.]+)\\.visualstudio\\.com/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$");

    public static RepoCoordinates parse(String repoUrl) {
        Matcher m;

        m = BB_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2));

        m = BB_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2));

        m = ADO_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3));

        m = ADO_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3));

        m = ADO_LEGACY.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3));

        throw new IllegalArgumentException("Cannot parse repository URL: " + repoUrl);
    }

    /**
     * Build an authenticated HTTPS clone URL.
     * Detects the platform from the fields: if {@code project} is empty the URL
     * targets Bitbucket Cloud, otherwise Azure DevOps.
     */
    public String httpsCloneUrl(String username, String password) {
        if (project == null || project.isEmpty()) {
            return "https://" + username + ":" + password
                    + "@bitbucket.org/" + organization + "/" + repository + ".git";
        }
        return "https://" + username + ":" + password
                + "@dev.azure.com/" + organization + "/" + project + "/_git/" + repository;
    }

    /**
     * Build the canonical web URL for this repository.
     */
    public String repoWebUrl() {
        if (project == null || project.isEmpty()) {
            return "https://bitbucket.org/" + organization + "/" + repository + ".git";
        }
        return "https://dev.azure.com/" + organization + "/" + project + "/_git/" + repository;
    }
}
