package com.eneve.agent.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts workspace and repo slug from a Bitbucket Cloud repo URL.
 */
public record RepoCoordinates(String workspace, String repoSlug) {

    private static final Pattern HTTPS_PATTERN =
            Pattern.compile("https?://(?:[^@]+@)?bitbucket\\.org/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SSH_PATTERN =
            Pattern.compile("git@bitbucket\\.org:([^/]+)/([^/]+?)(?:\\.git)?$");

    public static RepoCoordinates parse(String repoUrl) {
        Matcher m = HTTPS_PATTERN.matcher(repoUrl);
        if (m.matches()) {
            return new RepoCoordinates(m.group(1), m.group(2));
        }
        m = SSH_PATTERN.matcher(repoUrl);
        if (m.matches()) {
            return new RepoCoordinates(m.group(1), m.group(2));
        }
        throw new IllegalArgumentException("Cannot parse Bitbucket Cloud repo URL: " + repoUrl);
    }

    public String httpsCloneUrl(String username, String appPassword) {
        return "https://" + username + ":" + appPassword
                + "@bitbucket.org/" + workspace + "/" + repoSlug + ".git";
    }
}
