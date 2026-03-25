package com.eneve.agent.upgrade;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Scheduled job that runs daily to detect and upgrade outdated framework repositories.
 *
 * <p>Checks all supported archetypes: quarkus, dotnet, wildfly, angular, react, laravel,
 * symfony, and php.
 *
 * <p>Enabled via {@code upgrade.scheduler.enabled=true} (opt-in, default false).
 * Skips concurrent runs using {@link ConcurrentExecution#SKIP}.
 */
@ApplicationScoped
public class UpgradeScheduler {

    private static final Logger LOG = Logger.getLogger(UpgradeScheduler.class);

    @Inject
    UpgradeService upgradeService;

    @Inject
    SettingsService settings;

    @Scheduled(every = "24h", delayed = "10m",
               concurrentExecution = ConcurrentExecution.SKIP)
    void checkAndUpgrade() {
        if (!Boolean.parseBoolean(settings.get("upgrade.scheduler.enabled", "false"))) {
            return;
        }
        LOG.info("Upgrade scheduler triggered — checking all supported framework versions");
        UpgradeService.UpgradeResult result = upgradeService.checkAndUpgradeAll();
        LOG.infof("Upgrade scheduler complete: %d checked, %d outdated, %d plans created",
                result.checked(), result.outdated(), result.plansCreated());
    }
}
