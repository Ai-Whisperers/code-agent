package com.eneve.agent.bitbucket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bitbucket Cloud REST API 2.0 client.
 * Handles pull request creation, merging, and declining.
 */
@ApplicationScoped
public class BitbucketCloudService {

    private static final Logger LOG = Logger.getLogger(BitbucketCloudService.class);

    @ConfigProperty(name = "bitbucket.base.url", defaultValue = "https://api.bitbucket.org/2.0")
    String baseUrl;

    @ConfigProperty(name = "bitbucket.user")
    String bbUser;

    @ConfigProperty(name = "bitbucket.app.password")
    String appPassword;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Create a pull request. Returns the PR URL and PR ID as a two-element array [url, id].
     */
    public String[] createPullRequest(String workspace, String repoSlug,
                                      String sourceBranch, String targetBranch,
                                      String title, String description) {
        String body = """
                {
                  "title": "%s",
                  "description": "%s",
                  "source": { "branch": { "name": "%s" } },
                  "destination": { "branch": { "name": "%s" } },
                  "close_source_branch": false
                }
                """.formatted(
                escapeJson(title),
                escapeJson(description),
                escapeJson(sourceBranch),
                escapeJson(targetBranch)
        );

        String path = "/repositories/" + workspace + "/" + repoSlug + "/pullrequests";
        String responseBody = postAndReturn(path, body, "create PR");

        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String prUrl = node.path("links").path("html").path("href").asText();
            String prId = node.path("id").asText();
            LOG.infof("Created PR #%s: %s", prId, prUrl);
            return new String[] { prUrl, prId };
        } catch (Exception e) {
            LOG.errorf("Failed to parse create-PR response: %s", e.getMessage());
            return new String[] { "", "" };
        }
    }

    /**
     * Merge a pull request.
     */
    public void mergePullRequest(String workspace, String repoSlug, String prId) {
        String path = "/repositories/" + workspace + "/" + repoSlug
                + "/pullrequests/" + prId + "/merge";
        String body = """
                {
                  "merge_strategy": "merge_commit",
                  "close_source_branch": true
                }
                """;
        postAndReturn(path, body, "merge PR #" + prId);
        LOG.infof("Merged PR #%s in %s/%s", prId, workspace, repoSlug);
    }

    /**
     * Decline (reject) a pull request.
     */
    public void declinePullRequest(String workspace, String repoSlug, String prId) {
        String path = "/repositories/" + workspace + "/" + repoSlug
                + "/pullrequests/" + prId + "/decline";
        postAndReturn(path, "{}", "decline PR #" + prId);
        LOG.infof("Declined PR #%s in %s/%s", prId, workspace, repoSlug);
    }

    private String postAndReturn(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("Bitbucket %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.errorf("Bitbucket %s failed (HTTP %d) for user '%s': %s",
                        operation, response.statusCode(), bbUser, response.body());
                throw new RuntimeException("Bitbucket " + operation + " failed: HTTP "
                        + response.statusCode() + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf("Bitbucket %s error: %s", operation, e.getMessage());
            throw new RuntimeException("Bitbucket " + operation + " error: " + e.getMessage(), e);
        }
    }

    private String basicAuth() {
        return Base64.getEncoder()
                .encodeToString((bbUser + ":" + appPassword).getBytes(StandardCharsets.UTF_8));
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
