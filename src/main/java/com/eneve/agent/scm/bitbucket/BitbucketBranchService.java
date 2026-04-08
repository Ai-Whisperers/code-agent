package com.eneve.agent.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Bitbucket Cloud REST API 2.0 client for branch management operations.
 * <p>
 * Handles reading, creating, renaming (via create + delete), and setting the
 * default branch on a repository. Intended for use by
 * {@link com.eneve.agent.agent.BranchStandardizationService}.
 */
@ApplicationScoped
public class BitbucketBranchService {

    private static final Logger LOG = Logger.getLogger(BitbucketBranchService.class);

    @Inject
    SettingsService settings;

    private String baseUrl()     { return settings.get("bitbucket.base.url", "https://api.bitbucket.org/2.0"); }
    private String bbUser()    { return settings.get("bitbucket.user", ""); }
    private String appPassword() { return settings.getSecret("bitbucket.app.password"); }

    @Inject HttpClient httpClient;
    @Inject ObjectMapper objectMapper;

    /**
     * Returns the target commit hash of the named branch, or {@link Optional#empty()}
     * if the branch does not exist on the remote.
     */
    public Optional<String> getBranchHash(String workspace, String repo, String branch) {
        String path = "/repositories/" + workspace + "/" + repo + "/refs/branches/" + branch;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                String hash = node.path("target").path("hash").asText("");
                return hash.isEmpty() ? Optional.empty() : Optional.of(hash);
            }
            LOG.warnf("getBranchHash(%s/%s, %s) returned HTTP %d", workspace, repo, branch, response.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            LOG.errorf("getBranchHash(%s/%s, %s) error: %s", workspace, repo, branch, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the name of the repository's current default branch (e.g. {@code "main"}).
     */
    public Optional<String> getDefaultBranch(String workspace, String repo) {
        String path = "/repositories/" + workspace + "/" + repo;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                String name = node.path("mainbranch").path("name").asText("");
                return name.isEmpty() ? Optional.empty() : Optional.of(name);
            }
            LOG.warnf("getDefaultBranch(%s/%s) returned HTTP %d", workspace, repo, response.statusCode());
            return Optional.empty();
        } catch (Exception e) {
            LOG.errorf("getDefaultBranch(%s/%s) error: %s", workspace, repo, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Creates a new branch at the given commit hash.
     *
     * @throws RuntimeException if the API call fails
     */
    public void createBranch(String workspace, String repo, String branchName, String fromHash) {
        String path = "/repositories/" + workspace + "/" + repo + "/refs/branches";
        String body = """
                {
                  "name": "%s",
                  "target": { "hash": "%s" }
                }
                """.formatted(escapeJson(branchName), escapeJson(fromHash));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Created branch '%s' in %s/%s at %s", branchName, workspace, repo, fromHash);
            } else {
                throw new RuntimeException("createBranch failed: HTTP " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("createBranch error: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the repository's default (main) branch.
     *
     * @throws RuntimeException if the API call fails
     */
    public void setDefaultBranch(String workspace, String repo, String branchName) {
        String path = "/repositories/" + workspace + "/" + repo;
        String body = """
                {
                  "mainbranch": { "name": "%s" }
                }
                """.formatted(escapeJson(branchName));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Set default branch to '%s' in %s/%s", branchName, workspace, repo);
            } else {
                throw new RuntimeException("setDefaultBranch failed: HTTP " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("setDefaultBranch error: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a branch. If the branch does not exist (404) this is treated as a no-op.
     *
     * @throws RuntimeException if the API call fails for a reason other than 404
     */
    public void deleteBranch(String workspace, String repo, String branchName) {
        String path = "/repositories/" + workspace + "/" + repo + "/refs/branches/" + branchName;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(baseUrl() + path))
                    .header("Authorization", authHeader())
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                LOG.infof("Deleted branch '%s' from %s/%s", branchName, workspace, repo);
            } else if (response.statusCode() == 404) {
                LOG.debugf("Branch '%s' not found in %s/%s, nothing to delete", branchName, workspace, repo);
            } else {
                throw new RuntimeException("deleteBranch failed: HTTP " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("deleteBranch error: " + e.getMessage(), e);
        }
    }

    private String authHeader() {
        if ("x-token-auth".equals(bbUser())) {
            return "Bearer " + appPassword();
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString((bbUser() + ":" + appPassword()).getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
