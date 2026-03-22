package com.eneve.agent.webhooks;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.security.WebhookSignatureFilter;
import com.eneve.agent.upgrade.UpgradeService;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AikidoWebhookResource} and the Aikido-specific logic in
 * {@link WebhookSignatureFilter} (signature verification and replay-attack protection).
 *
 * <p>Dependencies are injected via reflection following the project's established test pattern.
 * The upgrade executor is replaced with a synchronous executor so upgrade calls are observable
 * without threading.
 */
class AikidoWebhookResourceTest {

    private AikidoWebhookResource resource;
    private AtomicReference<String> lastUpgradedWorkspace;
    private AtomicReference<String> lastUpgradedRepo;

    @BeforeEach
    void setUp() throws Exception {
        lastUpgradedWorkspace = new AtomicReference<>();
        lastUpgradedRepo = new AtomicReference<>();

        resource = new AikidoWebhookResource();

        // Synchronous executor so upgrade calls run on the test thread without delay
        setField(resource, "upgradeExecutor", syncExecutor());
    }

    // ─── Happy path ──────────────────────────────────────────────────────────────

    @Test
    void happyPath_repoMatchedByName_returns202AndTriggersUpgrade() throws Exception {
        RepoSettings repo = repo("myworkspace", "my-service");
        injectRepoStore(resource, List.of(repo));
        injectUpgradeService(resource, lastUpgradedWorkspace, lastUpgradedRepo);
        injectAikidoService(resource, false, null);

        String payload = buildPayload("issue.created", currentEpoch(),
                "{\"repo_name\": \"my-service\"}");

        Response response = resource.handleAikidoWebhook(payload);

        assertEquals(202, response.getStatus());
        assertEquals("myworkspace", lastUpgradedWorkspace.get());
        assertEquals("my-service", lastUpgradedRepo.get());
    }

    @Test
    void happyPath_repoMatchedByRepoUrl_returns202() throws Exception {
        RepoSettings repo = repo("csarenergy", "customer-portal-backend");
        injectRepoStore(resource, List.of(repo));
        injectUpgradeService(resource, lastUpgradedWorkspace, lastUpgradedRepo);
        injectAikidoService(resource, false, null);

        String payload = buildPayload("issue.updated", currentEpoch(),
                "{\"repo_url\": \"https://bitbucket.org/csarenergy/customer-portal-backend.git\"}");

        Response response = resource.handleAikidoWebhook(payload);

        assertEquals(202, response.getStatus());
        assertEquals("csarenergy", lastUpgradedWorkspace.get());
        assertEquals("customer-portal-backend", lastUpgradedRepo.get());
    }

    @Test
    void happyPath_repoMatchedViaContainerImage_returns202() throws Exception {
        RepoSettings repo = repo("csarenergy", "fit");
        injectRepoStore(resource, List.of(repo));
        injectUpgradeService(resource, lastUpgradedWorkspace, lastUpgradedRepo);
        // AikidoService returns a repo URL for the container
        injectAikidoService(resource, true,
                "https://bitbucket.org/csarenergy/fit.git");

        String payload = buildPayload("issue.created", currentEpoch(),
                "{\"container_image\": \"julesenergy/fit\"}");

        Response response = resource.handleAikidoWebhook(payload);

        assertEquals(202, response.getStatus());
        assertEquals("csarenergy", lastUpgradedWorkspace.get());
        assertEquals("fit", lastUpgradedRepo.get());
    }

    // ─── Unknown repo ─────────────────────────────────────────────────────────────

    @Test
    void unknownRepo_returns200Skipped() throws Exception {
        injectRepoStore(resource, List.of());
        injectUpgradeService(resource, lastUpgradedWorkspace, lastUpgradedRepo);
        injectAikidoService(resource, false, null);

        String payload = buildPayload("issue.created", currentEpoch(),
                "{\"repo_name\": \"nonexistent-repo\"}");

        Response response = resource.handleAikidoWebhook(payload);

        assertEquals(200, response.getStatus());
        assertNull(lastUpgradedWorkspace.get(), "Upgrade must not be triggered for unknown repo");
    }

    @Test
    void missingRepoFields_returns200Skipped() throws Exception {
        injectRepoStore(resource, List.of());
        injectUpgradeService(resource, lastUpgradedWorkspace, lastUpgradedRepo);
        injectAikidoService(resource, false, null);

        String payload = buildPayload("zen.attack", currentEpoch(), "{}");

        Response response = resource.handleAikidoWebhook(payload);

        assertEquals(200, response.getStatus());
        assertNull(lastUpgradedWorkspace.get());
    }

    // ─── parseWorkspaceAndSlug ───────────────────────────────────────────────────

    @Test
    void parseWorkspaceAndSlug_normalUrl_returnsWorkspaceAndSlug() {
        String[] parts = AikidoWebhookResource.parseWorkspaceAndSlug(
                "https://bitbucket.org/csarenergy/customer-portal-backend");
        assertNotNull(parts);
        assertEquals("csarenergy", parts[0]);
        assertEquals("customer-portal-backend", parts[1]);
    }

    @Test
    void parseWorkspaceAndSlug_withGitSuffix_stripsGit() {
        String[] parts = AikidoWebhookResource.parseWorkspaceAndSlug(
                "https://bitbucket.org/csarenergy/fit.git");
        assertNotNull(parts);
        assertEquals("csarenergy", parts[0]);
        assertEquals("fit", parts[1]);
    }

    @Test
    void parseWorkspaceAndSlug_insufficientSegments_returnsNull() {
        assertNull(AikidoWebhookResource.parseWorkspaceAndSlug("https://bitbucket.org/only-one-segment"));
        assertNull(AikidoWebhookResource.parseWorkspaceAndSlug(null));
        assertNull(AikidoWebhookResource.parseWorkspaceAndSlug(""));
    }

    // ─── Replay-attack protection ────────────────────────────────────────────────

    @Test
    void freshTimestamp_isAccepted() {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        assertTrue(WebhookSignatureFilter.isTimestampFresh(nowSeconds),
                "A timestamp equal to now should be accepted");
    }

    @Test
    void recentTimestamp_withinWindow_isAccepted() {
        long recentSeconds = System.currentTimeMillis() / 1000L - 29;
        assertTrue(WebhookSignatureFilter.isTimestampFresh(recentSeconds),
                "A timestamp 29 seconds old should be accepted");
    }

    @Test
    void staleTimestamp_olderThan30Seconds_isRejected() {
        long staleSeconds = System.currentTimeMillis() / 1000L - 60;
        assertFalse(WebhookSignatureFilter.isTimestampFresh(staleSeconds),
                "A timestamp 60 seconds old should be rejected");
    }

    @Test
    void zeroTimestamp_isRejected() {
        assertFalse(WebhookSignatureFilter.isTimestampFresh(0L),
                "Epoch zero is far in the past and must be rejected");
    }

    // ─── Signature verification ──────────────────────────────────────────────────

    @Test
    void hmacSignature_isDeteministic() {
        byte[] body = "{\"event_type\":\"issue.created\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "test-webhook-secret";

        String hex1 = WebhookSignatureFilter.hmacSha256Hex(body, secret);
        String hex2 = WebhookSignatureFilter.hmacSha256Hex(body, secret);

        assertNotNull(hex1);
        assertEquals(64, hex1.length(), "SHA-256 digest must be 64 hex characters");
        assertEquals(hex1, hex2, "HMAC is deterministic for the same input");
    }

    @Test
    void hmacSignature_differentSecret_producesDifferentHex() {
        byte[] body = "{\"event_type\":\"issue.created\"}".getBytes(StandardCharsets.UTF_8);

        String hex1 = WebhookSignatureFilter.hmacSha256Hex(body, "secret-a");
        String hex2 = WebhookSignatureFilter.hmacSha256Hex(body, "secret-b");

        assertNotEquals(hex1, hex2, "Different secrets must produce different HMAC digests");
    }

    @Test
    void hmacSignature_differentPayload_producesDifferentHex() {
        String secret = "shared-secret";
        byte[] body1 = "{\"event_type\":\"issue.created\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "{\"event_type\":\"issue.resolved\"}".getBytes(StandardCharsets.UTF_8);

        String hex1 = WebhookSignatureFilter.hmacSha256Hex(body1, secret);
        String hex2 = WebhookSignatureFilter.hmacSha256Hex(body2, secret);

        assertNotEquals(hex1, hex2, "Different payloads must produce different HMAC digests");
    }

    @Test
    void hmacSignature_outputIsHexOnly() {
        byte[] body = "Hello, Aikido!".getBytes(StandardCharsets.UTF_8);
        String hex = WebhookSignatureFilter.hmacSha256Hex(body, "mysecret");
        assertTrue(hex.matches("[0-9a-f]{64}"),
                "Output must be 64 lowercase hex characters (SHA-256 digest)");
    }

    // ─── Fixtures and helpers ─────────────────────────────────────────────────────

    private static RepoSettings repo(String workspace, String repoSlug) {
        return new RepoSettings(1L, workspace, repoSlug,
                true, false, true, true, false, false,
                List.of(), null, List.of(), null, null, null,
                "quarkus", "3.8", Map.of(), Instant.now(), Instant.now());
    }

    private static long currentEpoch() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String buildPayload(String eventType, long dispatchedAt, String payloadJson) {
        return String.format(
                "{\"event_type\":\"%s\",\"dispatched_at\":%d,\"payload\":%s}",
                eventType, dispatchedAt, payloadJson);
    }

    private static void injectRepoStore(AikidoWebhookResource resource,
                                         List<RepoSettings> repos) throws Exception {
        RepoSettingsStore stub = new RepoSettingsStore() {
            @Override public List<RepoSettings> listAll() { return repos; }

            @Override public Optional<RepoSettings> find(String workspace, String repoSlug) {
                return repos.stream()
                        .filter(r -> r.workspace().equals(workspace) && r.repoSlug().equals(repoSlug))
                        .findFirst();
            }
        };
        setField(resource, "repoSettingsStore", stub);
    }

    private static void injectUpgradeService(AikidoWebhookResource resource,
                                              AtomicReference<String> workspace,
                                              AtomicReference<String> repoSlug) throws Exception {
        UpgradeService stub = new UpgradeService() {
            @Override
            public UpgradeResult checkAndUpgradeOne(String ws, String slug) {
                workspace.set(ws);
                repoSlug.set(slug);
                return new UpgradeResult(1, 1, 1, List.of("plan-test-id"));
            }
        };
        setField(resource, "upgradeService", stub);
    }

    private static void injectAikidoService(AikidoWebhookResource resource,
                                             boolean enabled,
                                             String containerRepoUrl) throws Exception {
        AikidoService stub = new AikidoService() {
            @Override public boolean isEnabled() { return enabled; }

            @Override public String findCodeRepoUrlForContainer(String containerImage) {
                return containerRepoUrl;
            }
        };
        setField(resource, "aikidoService", stub);
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }

    /** Returns an {@link ExecutorService} that runs tasks synchronously on the calling thread. */
    private static ExecutorService syncExecutor() {
        return new ExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public <T> Future<T> submit(Callable<T> task) {
                try { task.call(); } catch (Exception ignored) {}
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public <T> Future<T> submit(Runnable task, T result) {
                task.run();
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            }
            @Override public Future<?> submit(Runnable task) {
                task.run();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) { return List.of(); }
            @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return List.of(); }
            @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) { return null; }
            @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return null; }
        };
    }
}
