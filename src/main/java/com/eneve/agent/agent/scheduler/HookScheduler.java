package com.eneve.agent.agent.scheduler;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.model.AutomationHook;
import com.eneve.agent.agent.model.TriggerType;
import com.eneve.agent.agent.store.HookStore;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Scheduler that evaluates cron-based automation hooks.
 * Runs every minute to check for hooks with cron expressions that should fire.
 */
@ApplicationScoped
public class HookScheduler {

    private static final Logger LOG = Logger.getLogger(HookScheduler.class);

    @Inject HookStore hookStore;
    @Inject HookEvaluator hookEvaluator;

    @ConfigProperty(name = "hook.scheduler.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "hook.scheduler.timezone", defaultValue = "UTC")
    String timezone;

    @Scheduled(every = "1m", concurrentExecution = ConcurrentExecution.SKIP)
    void evaluateCronHooks() {
        if (!enabled) {
            LOG.trace("Hook scheduler is disabled");
            return;
        }

        try {
            LOG.trace("Hook scheduler: checking cron hooks");
            
            List<AutomationHook> cronHooks = hookStore.findByTriggerType(TriggerType.CRON);
            if (cronHooks.isEmpty()) {
                LOG.trace("No cron hooks configured");
                return;
            }

            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
            LOG.debugf("Hook scheduler: evaluating %d cron hooks at %s", 
                      cronHooks.size(), now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            int triggeredCount = 0;
            for (AutomationHook hook : cronHooks) {
                if (shouldTriggerCronHook(hook, now)) {
                    triggerCronHook(hook, now);
                    triggeredCount++;
                }
            }

            if (triggeredCount > 0) {
                LOG.infof("Hook scheduler: triggered %d cron hooks", triggeredCount);
            } else {
                LOG.trace("Hook scheduler: no cron hooks fired");
            }

        } catch (Exception e) {
            LOG.errorf("Hook scheduler error: %s", e.getMessage());
        }
    }

    private boolean shouldTriggerCronHook(AutomationHook hook, ZonedDateTime now) {
        String cronExpr = hook.cronExpr();
        if (cronExpr == null || cronExpr.isBlank()) {
            LOG.warnf("Hook '%s' has cron trigger type but no cron expression", hook.name());
            return false;
        }

        try {
            // Simple cron evaluation - supports basic patterns like "0 8 * * *" (daily at 8am)
            return evaluateCronExpression(cronExpr, now);
        } catch (Exception e) {
            LOG.warnf("Invalid cron expression for hook '%s': %s", hook.name(), e.getMessage());
            return false;
        }
    }

    private boolean evaluateCronExpression(String cronExpr, ZonedDateTime now) {
        // Simple cron parser for basic expressions
        // Format: "minute hour day month dayOfWeek"
        String[] parts = cronExpr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 parts: " + cronExpr);
        }

        int minute = now.getMinute();
        int hour = now.getHour();
        int day = now.getDayOfMonth();
        int month = now.getMonthValue();
        int dayOfWeek = now.getDayOfWeek().getValue() % 7; // Convert to 0=Sunday, 1=Monday, etc.

        return matchesCronField(parts[0], minute) &&    // minute
               matchesCronField(parts[1], hour) &&      // hour
               matchesCronField(parts[2], day) &&       // day of month
               matchesCronField(parts[3], month) &&     // month
               matchesCronField(parts[4], dayOfWeek);   // day of week
    }

    private boolean matchesCronField(String field, int value) {
        if ("*".equals(field)) {
            return true;
        }

        // Handle single values
        try {
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            // Could extend to support ranges, lists, etc. in the future
            LOG.warnf("Unsupported cron field format: %s", field);
            return false;
        }
    }

    private void triggerCronHook(AutomationHook hook, ZonedDateTime now) {
        LOG.infof("Triggering cron hook '%s' at %s", hook.name(), 
                 now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // Build context with timing information
        var context = Map.of(
                "triggerTime", now.toInstant().toString(),
                "cronExpression", hook.cronExpr() != null ? hook.cronExpr() : "",
                "timezone", timezone
        );

        // Use hook's repoUrl to determine workspace/repo, or fall back to defaults
        String workspace = "scheduled";
        String repoSlug = "cron";
        String repoUrl = hook.repoUrl();

        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                // Try to extract workspace/repo from URL
                String[] urlParts = repoUrl.replace(".git", "").split("/");
                if (urlParts.length >= 2) {
                    workspace = urlParts[urlParts.length - 2];
                    repoSlug = urlParts[urlParts.length - 1];
                }
            } catch (Exception e) {
                LOG.debugf("Could not parse workspace/repo from URL '%s': %s", repoUrl, e.getMessage());
            }
        }

        try {
            var jobIds = hookEvaluator.evaluateByTrigger(
                    TriggerType.CRON, workspace, repoSlug, repoUrl, context);
            
            if (!jobIds.isEmpty()) {
                LOG.infof("Cron hook '%s' triggered %d jobs: %s", hook.name(), jobIds.size(), jobIds);
            } else {
                LOG.debugf("Cron hook '%s' did not trigger any jobs", hook.name());
            }
        } catch (Exception e) {
            LOG.errorf("Failed to trigger cron hook '%s': %s", hook.name(), e.getMessage());
        }
    }
}
