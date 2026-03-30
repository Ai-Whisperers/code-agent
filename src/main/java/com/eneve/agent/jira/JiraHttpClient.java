package com.eneve.agent.jira;

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
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Low-level HTTP transport for Jira REST API calls.
 * All methods are package-private — callers must go through {@link JiraService}.
 */
@ApplicationScoped
class JiraHttpClient {

    private static final Logger LOG = Logger.getLogger(JiraHttpClient.class);

    @Inject SettingsService settingsService;
    @Inject HttpClient httpClient;

    String get(String path, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(settingsService.get("jira.base.url", "") + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    String postForBody(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(settingsService.get("jira.base.url", "") + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    void post(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(settingsService.get("jira.base.url", "") + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
        }
    }

    String putForBody(String path, String body, String operation) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(settingsService.get("jira.base.url", "") + path))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.infof("JIRA %s succeeded (HTTP %d)", operation, response.statusCode());
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    /** Builds the correct Authorization header value for any auth type. */
    private static String authHeader(JiraService.JiraCredentials creds) {
        if (creds.isOAuth()) {
            return "Bearer " + creds.apiToken();
        }
        String encoded = Base64.getEncoder()
                .encodeToString((creds.username() + ":" + creds.apiToken()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    String getWithCreds(String path, String operation, JiraService.JiraCredentials creds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", authHeader(creds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    String postForBodyWithCreds(String path, String body, String operation, JiraService.JiraCredentials creds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", authHeader(creds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            } else {
                LOG.warnf("JIRA %s failed (HTTP %d): %s", operation, response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return null;
        }
    }

    boolean postWithCreds(String path, String body, String operation, JiraService.JiraCredentials creds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", authHeader(creds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return false;
        }
    }

    boolean putWithCreds(String path, String body, String operation, JiraService.JiraCredentials creds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(creds.baseUrl() + path))
                    .header("Authorization", authHeader(creds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            LOG.errorf("JIRA %s error: %s", operation, e.getMessage());
            return false;
        }
    }

    byte[] downloadAttachment(String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(contentUrl))
                    .header("Authorization", "Basic " + basicAuth())
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            LOG.warnf("Attachment download failed (HTTP %d) for %s", response.statusCode(), contentUrl);
            return null;
        } catch (Exception e) {
            LOG.errorf("Attachment download error for %s: %s", contentUrl, e.getMessage());
            return null;
        }
    }

    String basicAuth() {
        return Base64.getEncoder()
                .encodeToString((settingsService.get("jira.user", "") + ":" + settingsService.getSecret("jira.api.token"))
                        .getBytes(StandardCharsets.UTF_8));
    }

    boolean isConfigured() {
        return !settingsService.get("jira.base.url", "").isBlank()
                && !settingsService.get("jira.user", "").isBlank()
                && !settingsService.getSecret("jira.api.token").isBlank();
    }

    String getBaseUrl() { return settingsService.get("jira.base.url", ""); }
    String getUser()    { return settingsService.get("jira.user", ""); }
    String getApiToken() { return settingsService.getSecret("jira.api.token"); }

    static boolean testConnection(String testBaseUrl, String testUser, String testApiToken) {
        try {
            String auth = Base64.getEncoder()
                    .encodeToString((testUser + ":" + testApiToken).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(testBaseUrl + "/rest/api/3/myself"))
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** Tests a Jira connection using an OAuth Bearer access token. */
    static boolean testConnectionOAuth(String testBaseUrl, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(30))
                    .uri(URI.create(testBaseUrl + "/rest/api/3/myself"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static java.time.Instant parseJiraTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            try {
                return java.time.Instant.parse(value);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
