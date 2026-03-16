package com.eneve.agent.agent;

import java.util.concurrent.ConcurrentLinkedDeque;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tracks Anthropic API token consumption in a sliding one-minute window and
 * proactively throttles callers before they hit a rate-limit 429.
 * <p>
 * Calling {@link #waitIfNeeded()} before every API call keeps consumption just
 * below the configured safety margin, avoiding the 30-second (or longer) backoff
 * penalties that follow a rate-limit error.
 * <p>
 * Thread-safe: the underlying deque is a {@link ConcurrentLinkedDeque} and all
 * mutations are guarded by lightweight reads of {@code System.currentTimeMillis()}.
 */
@ApplicationScoped
public class TokenBudgetTracker {

    private static final Logger LOG = Logger.getLogger(TokenBudgetTracker.class);
    private static final long WINDOW_MS = 60_000L;

    @ConfigProperty(name = "anthropic.rate-limit.tokens-per-minute", defaultValue = "80000")
    long tokensPerMinute;

    @ConfigProperty(name = "anthropic.rate-limit.safety-margin", defaultValue = "0.80")
    double safetyMargin;

    private record TokenEntry(long timestampMs, long tokens) {}

    private final ConcurrentLinkedDeque<TokenEntry> window = new ConcurrentLinkedDeque<>();

    /**
     * Record actual token usage after a completed API call.
     * Must be called with the sum of input and output tokens from the API response.
     */
    public void recordUsage(long inputTokens, long outputTokens) {
        window.addLast(new TokenEntry(System.currentTimeMillis(), inputTokens + outputTokens));
        pruneWindow();
    }

    /**
     * Sleep the calling thread if the rolling one-minute token usage is at or
     * above the configured safety threshold, waiting just long enough for the
     * oldest window entry to expire and open headroom.
     *
     * @throws InterruptedException if the sleep is interrupted
     */
    public void waitIfNeeded() throws InterruptedException {
        pruneWindow();
        long used = sumWindow();
        long limit = (long) (tokensPerMinute * safetyMargin);
        if (used < limit) {
            return;
        }

        TokenEntry oldest = window.peekFirst();
        if (oldest == null) {
            return;
        }
        long expireAt = oldest.timestampMs() + WINDOW_MS;
        long waitMs = expireAt - System.currentTimeMillis();
        if (waitMs > 0) {
            LOG.infof("Token budget pre-throttle: used %d / %d tokens in sliding window — sleeping %dms",
                    used, limit, waitMs);
            Thread.sleep(waitMs);
            pruneWindow();
        }
    }

    private long sumWindow() {
        return window.stream().mapToLong(TokenEntry::tokens).sum();
    }

    private void pruneWindow() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst().timestampMs() < cutoff) {
            window.pollFirst();
        }
    }
}
