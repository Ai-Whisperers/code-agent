package com.eneve.agent.xray;

import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Xray Cloud API client.
 *
 * <p>Authenticates via Xray Cloud's OAuth2 client-credentials endpoint
 * ({@code POST /api/v1/authenticate}), caches the resulting bearer token for 23 h
 * (Xray issues 24 h tokens), then dispatches GraphQL requests to
 * {@code /api/v2/graphql}.
 *
 * <p>System-level credentials are read from {@link SettingsService} using the
 * conventional keys {@code xray.client-id}, {@code xray.client-secret}, and
 * {@code xray.base-url}. These can be set via the existing {@code /settings}
 * REST endpoint and are used by schedulers and jobs that run without a user context.
 */
@ApplicationScoped
public class XrayService {

    private static final Logger LOG = Logger.getLogger(XrayService.class);

    /** Cache tokens for 23 h — Xray issues them with a 24 h TTL. */
    private static final int TOKEN_TTL_SECONDS = 23 * 60 * 60;

    private static final String DEFAULT_BASE_URL = "https://xray.cloud.getxray.app";

    @Inject
    SettingsService settingsService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Thread-safe bearer-token cache keyed by {@code clientId}. */
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    // ─── Credential & result records ─────────────────────────────────────────────

    public record XrayCredentials(String clientId, String clientSecret, String baseUrl) {
        public String graphqlUrl() { return baseUrl + "/api/v2/graphql"; }
        public String authUrl()    { return baseUrl + "/api/v1/authenticate"; }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    public record XrayTest(String issueId, String key, String summary, String status) {}

    public record XrayTestRun(
            String id,
            String testKey,
            String testSummary,
            String status,
            String startedOn,
            String finishedOn) {}

    public record XrayTestExecution(
            String issueId,
            String key,
            String summary,
            List<XrayTestRun> testRuns) {}

    // ─── System credentials ───────────────────────────────────────────────────────

    /**
     * Returns {@code true} when all three system credential settings are non-blank.
     * Setting keys: {@code xray.client-id}, {@code xray.client-secret}, {@code xray.base-url}.
     */
    public boolean isConfigured() {
        return !settingsService.get("xray.client-id", "").isBlank()
                && !settingsService.getSecret("xray.client-secret").isBlank()
                && !settingsService.get("xray.base-url", "").isBlank();
    }

    public String getClientId()     { return settingsService.get("xray.client-id", ""); }
    public String getClientSecret() { return settingsService.getSecret("xray.client-secret"); }
    public String getSystemBaseUrl(){ return settingsService.get("xray.base-url", DEFAULT_BASE_URL); }

    /** Convenience factory for the system-credential path (used by schedulers / jobs). */
    public XrayCredentials systemCredentials() {
        return new XrayCredentials(getClientId(), getClientSecret(), getSystemBaseUrl());
    }

    // ─── Authentication ───────────────────────────────────────────────────────────

    /**
     * Obtains (or returns from cache) a bearer token for the given credentials.
     *
     * <p>Because the auth call is the only reliable way to validate Xray Cloud
     * credentials (there is no separate health/ping endpoint), this method also
     * serves as the connection test: a non-null return value means credentials are valid.
     *
     * @return the bearer token, or {@code null} on auth failure
     */
    public String authenticate(XrayCredentials creds) {
        CachedToken cached = tokenCache.get(creds.clientId());
        if (cached != null && !cached.isExpired()) {
            return cached.token();
        }

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "client_id", creds.clientId(),
                    "client_secret", creds.clientSecret()
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.authUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Xray auth failed (HTTP %d): %s", response.statusCode(), response.body());
                return null;
            }

            // Response body is a plain quoted JWT string, e.g. "eyJ..."
            String token = response.body().replace("\"", "").trim();
            tokenCache.put(creds.clientId(),
                    new CachedToken(token, Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));
            LOG.debugf("Xray token obtained for clientId=%s", creds.clientId());
            return token;
        } catch (Exception e) {
            LOG.errorf("Xray authentication error: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Static connection test — does not share the instance token cache.
     * Used by {@link com.eneve.agent.mcp.LinkedAccountService} before saving credentials.
     */
    public static boolean testConnection(String baseUrl, String clientId, String clientSecret) {
        try {
            ObjectMapper om = new ObjectMapper();
            String body = om.writeValueAsString(Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret
            ));
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/authenticate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── GraphQL executor ─────────────────────────────────────────────────────────

    private JsonNode graphql(String query, Map<String, Object> variables, XrayCredentials creds) {
        String token = authenticate(creds);
        if (token == null) return null;

        try {
            String body = mapper.writeValueAsString(Map.of(
                    "query", query,
                    "variables", variables
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(creds.graphqlUrl()))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Xray GraphQL failed (HTTP %d): %s", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            if (root.has("errors")) {
                LOG.warnf("Xray GraphQL returned errors: %s", root.path("errors"));
                return null;
            }
            return root.path("data");
        } catch (Exception e) {
            LOG.errorf("Xray GraphQL error: %s", e.getMessage());
            return null;
        }
    }

    // ─── Public API methods ───────────────────────────────────────────────────────

    /**
     * Search test cases using JQL, e.g. {@code project = PROJ AND labels = regression}.
     */
    public List<XrayTest> searchTests(String jql, int maxResults, XrayCredentials creds) {
        String query = """
                query getTests($jql: String, $limit: Int) {
                  getTests(jql: $jql, limit: $limit) {
                    total
                    results {
                      issueId
                      status { name }
                      jira(fields: ["key", "summary"])
                    }
                  }
                }
                """;
        JsonNode data = graphql(query, Map.of("jql", jql, "limit", maxResults), creds);
        if (data == null) return List.of();

        List<XrayTest> results = new ArrayList<>();
        for (JsonNode node : data.path("getTests").path("results")) {
            String issueId = node.path("issueId").asText("");
            String status  = node.path("status").path("name").asText("");
            JsonNode jira  = node.path("jira");
            results.add(new XrayTest(issueId, jira.path("key").asText(""), jira.path("summary").asText(""), status));
        }
        return results;
    }

    /**
     * Search test executions using JQL, e.g. {@code project = PROJ AND created >= -7d}.
     */
    public List<XrayTest> searchTestExecutions(String jql, int maxResults, XrayCredentials creds) {
        String query = """
                query getTestExecutions($jql: String, $limit: Int) {
                  getTestExecutions(jql: $jql, limit: $limit) {
                    total
                    results {
                      issueId
                      jira(fields: ["key", "summary", "status"])
                    }
                  }
                }
                """;
        JsonNode data = graphql(query, Map.of("jql", jql, "limit", maxResults), creds);
        if (data == null) return List.of();

        List<XrayTest> results = new ArrayList<>();
        for (JsonNode node : data.path("getTestExecutions").path("results")) {
            String issueId = node.path("issueId").asText("");
            JsonNode jira  = node.path("jira");
            String status  = jira.path("status").path("name").asText("");
            results.add(new XrayTest(issueId, jira.path("key").asText(""), jira.path("summary").asText(""), status));
        }
        return results;
    }

    /**
     * Fetch a single test execution with all its test run results.
     *
     * @param issueKey Jira issue key of the test execution, e.g. {@code PROJ-456}
     */
    public XrayTestExecution getTestExecution(String issueKey, XrayCredentials creds) {
        String query = """
                query getTestExecution($jql: String) {
                  getTestExecutions(jql: $jql, limit: 1) {
                    results {
                      issueId
                      jira(fields: ["key", "summary"])
                      testRuns(limit: 100) {
                        total
                        results {
                          id
                          status { name }
                          startedOn
                          finishedOn
                          test {
                            jira(fields: ["key", "summary"])
                          }
                        }
                      }
                    }
                  }
                }
                """;
        String jql = "issueKey = \"" + issueKey + "\"";
        JsonNode data = graphql(query, Map.of("jql", jql), creds);
        if (data == null) return null;

        JsonNode resultArr = data.path("getTestExecutions").path("results");
        if (!resultArr.isArray() || resultArr.isEmpty()) return null;

        JsonNode exec      = resultArr.get(0);
        String issueId     = exec.path("issueId").asText("");
        JsonNode execJira  = exec.path("jira");
        String key         = execJira.path("key").asText(issueKey);
        String summary     = execJira.path("summary").asText("");

        List<XrayTestRun> runs = new ArrayList<>();
        for (JsonNode run : exec.path("testRuns").path("results")) {
            JsonNode testJira = run.path("test").path("jira");
            runs.add(new XrayTestRun(
                    run.path("id").asText(""),
                    testJira.path("key").asText(""),
                    testJira.path("summary").asText(""),
                    run.path("status").path("name").asText(""),
                    run.path("startedOn").asText(""),
                    run.path("finishedOn").asText("")
            ));
        }
        return new XrayTestExecution(issueId, key, summary, runs);
    }

    /**
     * Create a new test execution containing the specified test issues.
     *
     * @param testIssueIds list of Jira issue IDs (numeric strings) for the test cases to include
     * @return the Jira key of the created test execution, or {@code null} on failure
     */
    public String createTestExecution(String projectKey, String summary,
                                       List<String> testIssueIds, XrayCredentials creds) {
        String query = """
                mutation createTestExecution($jira: JSON!, $tests: [String]) {
                  createTestExecution(jira: $jira, tests: $tests) {
                    testExecution {
                      issueId
                      jira(fields: ["key", "summary"])
                    }
                    warnings
                  }
                }
                """;
        Map<String, Object> jiraFields = Map.of(
                "summary", summary,
                "project", Map.of("key", projectKey)
        );
        Map<String, Object> variables = new HashMap<>();
        variables.put("jira", jiraFields);
        variables.put("tests", testIssueIds);

        JsonNode data = graphql(query, variables, creds);
        if (data == null) return null;

        JsonNode execNode = data.path("createTestExecution").path("testExecution");
        if (execNode.isMissingNode()) return null;
        return execNode.path("jira").path("key").asText(null);
    }

    /**
     * Update the status of a single test run within a test execution.
     *
     * @param testRunId internal Xray test run ID (returned by {@link #getTestExecution})
     * @param status    status name, e.g. {@code PASS}, {@code FAIL}, {@code TODO}, {@code EXECUTING}
     */
    public boolean updateTestRunStatus(String testRunId, String status, XrayCredentials creds) {
        String query = """
                mutation updateTestRunStatus($id: String!, $status: String!) {
                  updateTestRunStatus(id: $id, status: $status)
                }
                """;
        JsonNode data = graphql(query, Map.of("id", testRunId, "status", status), creds);
        return data != null;
    }

    /**
     * Determine test coverage for a list of Jira issue keys.
     *
     * <p>Queries Xray for tests linked to each requirement via
     * {@code issue in coveredBy("KEY")} and returns a map of
     * {@code issueKey -> List<XrayTest>} (empty list = no coverage).
     *
     * <p>Makes one GraphQL call per issue key — suitable for lists up to ~20 keys.
     */
    public Map<String, List<XrayTest>> getTestCoverage(List<String> issueKeys, XrayCredentials creds) {
        Map<String, List<XrayTest>> coverage = new LinkedHashMap<>();
        for (String key : issueKeys) {
            String jql = "issue in coveredBy(\"" + key + "\")";
            coverage.put(key, searchTests(jql, 10, creds));
        }
        return coverage;
    }
}
