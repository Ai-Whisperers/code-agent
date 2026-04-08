package com.eneve.agent.notifications;

import com.eneve.agent.model.RunResult;

/**
 * One-way outbound notification channel for job lifecycle events.
 *
 * <p>Implementations are auto-discovered by {@link NotificationDispatcher}
 * via CDI {@code Instance<Notifier>}. Each implementation is responsible
 * for its own enable/disable logic (typically by returning early if its
 * config is blank) so the dispatcher can fan out unconditionally.
 *
 * <p>Implementations MUST be {@code @ApplicationScoped} so CDI picks them up.
 *
 * <p>Failure in one notifier MUST NOT prevent other notifiers from running —
 * implementations catch their own exceptions and log.
 */
public interface Notifier {

    /**
     * Channel identifier for logging and dispatcher reporting.
     * Examples: "teams", "hermes", "telegram-direct", "email".
     */
    String channel();

    /**
     * Send a job result to this channel. Must not throw — catch and log
     * internally so other notifiers still run.
     */
    void sendNotification(RunResult result);
}
