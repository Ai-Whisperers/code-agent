package com.eneve.agent.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts organization, project, and repository from a git hosting platform URL.
 * <p>
 * For Bitbucket Cloud and GitLab Cloud the {@code project} field is empty since both use
 * a flat namespace/repo hierarchy. For Azure DevOps all three fields are populated.
 * <p>
 * Parameter mapping per platform:
 * <ul>
 *   <li>Bitbucket Cloud: org = workspace,    project = "" (ignored), repo = repoSlug</li>
 *   <li>Azure DevOps:    org = organization, project = project name, repo = repository name</li>
 *   <li>GitLab Cloud:    org = namespace,    project = "" (ignored), repo = project slug</li>
 *   <li>GitHub:          org = owner,        project = "" (ignored), repo = repository name</li>
 * </ul>
 */
public record RepoCoordinates(String organization, String project, String repository, Platform platform) {

    /** The hosting platform inferred from the clone URL. */
    public enum Platform {
        BITBUCKET,
        AZURE_DEVOPS,
        GITLAB,
        GITHUB
    }

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

    // ── GitLab Cloud ─────────────────────────────────────────────────────
    // https://gitlab.com/{namespace}/{repo}   (namespace may contain subgroups: a/b/c/repo)
    // The last path segment is the repo slug; everything before it is the namespace.
    private static final Pattern GL_HTTPS =
            Pattern.compile("https?://(?:[^@]+@)?gitlab\\.com/(.+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern GL_SSH =
            Pattern.compile("git@gitlab\\.com:(.+)/([^/]+?)(?:\\.git)?$");

    // ── GitHub ────────────────────────────────────────────────────────────
    // https://github.com/{owner}/{repo}
    private static final Pattern GH_HTTPS =
            Pattern.compile("https?://(?:[^@]+@)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern GH_SSH =
            Pattern.compile("git@github\\.com:([^/]+)/([^/]+?)(?:\\.git)?$");

    public static RepoCoordinates parse(String repoUrl) {
        Matcher m;

        m = BB_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.BITBUCKET);

        m = BB_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.BITBUCKET);

        m = ADO_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3), Platform.AZURE_DEVOPS);

        m = ADO_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3), Platform.AZURE_DEVOPS);

        m = ADO_LEGACY.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), m.group(2), m.group(3), Platform.AZURE_DEVOPS);

        m = GL_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.GITLAB);

        m = GL_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.GITLAB);

        m = GH_HTTPS.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.GITHUB);

        m = GH_SSH.matcher(repoUrl);
        if (m.matches()) return new RepoCoordinates(m.group(1), "", m.group(2), Platform.GITHUB);

        throw new IllegalArgumentException("Cannot parse repository URL: " + repoUrl);
    }

    /**
     * Build an authenticated HTTPS clone URL.
     * Detects the platform from the {@code platform} field.
     */
    public String httpsCloneUrl(String username, String password) {
        return switch (platform) {
            case BITBUCKET -> "https://" + username + ":" + password
                    + "@bitbucket.org/" + organization + "/" + repository + ".git";
            case AZURE_DEVOPS -> "https://" + username + ":" + password
                    + "@dev.azure.com/" + organization + "/" + project + "/_git/" + repository;
            case GITLAB -> "https://" + username + ":" + password
                    + "@gitlab.com/" + organization + "/" + repository + ".git";
            case GITHUB -> "https://" + username + ":" + password
                    + "@github.com/" + organization + "/" + repository + ".git";
        };
    }

    /**
     * Build the canonical web URL for this repository.
     */
    public String repoWebUrl() {
        return switch (platform) {
            case BITBUCKET -> "https://bitbucket.org/" + organization + "/" + repository + ".git";
            case AZURE_DEVOPS -> "https://dev.azure.com/" + organization + "/" + project + "/_git/" + repository;
            case GITLAB -> "https://gitlab.com/" + organization + "/" + repository;
            case GITHUB -> "https://github.com/" + organization + "/" + repository;
        };
    }
}
