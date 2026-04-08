package com.eneve.agent.notifications;

import com.eneve.agent.model.RunResult;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Fan-out notification dispatcher.
 *
 * <p>Auto-discovers every CDI bean implementing {@link Notifier} and invokes
 * each one for every job lifecycle event. Notifiers are independent — if one
 * fails or is disabled, the others still run.
 *
 * <p>Replaces the old pattern of every call site directly injecting
 * {@code TeamsNotifier}. Call sites now inject this dispatcher instead, and
 * adding a new channel (Hermes, Telegram-direct, Slack, email) is purely
 * additive — create a new {@code @ApplicationScoped} class implementing
 * {@code Notifier} and the dispatcher picks it up automatically.
 *
 * <p>Registered notifiers include:
 * <ul>
 *   <li>{@link TeamsNotifier} — Microsoft Teams incoming webhook (Eneve legacy,
 *       disabled unless {@code teams.webhook.url} is set)</li>
 *   <li>{@link HermesGatewayNotifier} — AIW Hermes gateway, which routes to
 *       Telegram / Discord / WhatsApp / Signal / etc. (disabled unless
 *       {@code hermes.gateway.url} is set)</li>
 * </ul>
 */
@ApplicationScoped
public class NotificationDispatcher {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class);

    @Inject
    Instance<Notifier> notifiers;

    /**
     * Send the given job result to every registered notifier.
     * Errors in individual notifiers are swallowed and logged — the dispatcher
     * never throws.
     */
    public void sendNotification(RunResult result) {
        if (result == null) {
            return;
        }
        for (Notifier notifier : notifiers) {
            try {
                notifier.sendNotification(result);
            } catch (Exception e) {
                // Individual notifiers should handle their own exceptions, but
                // belt-and-braces: a misbehaving notifier must not block the others.
                LOG.errorf("Notifier [%s] threw: %s", notifier.channel(), e.getMessage());
            }
        }
    }
}
