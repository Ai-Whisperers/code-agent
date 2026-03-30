package com.eneve.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class JobQueueHealthCheck implements HealthCheck {

    @Inject
    RunFixService runFixService;

    @Override
    public HealthCheckResponse call() {
        var data = runFixService.health();
        return HealthCheckResponse.named("job-queue")
                .status("UP".equals(data.get("status")))
                .withData("availableSlots",    (long) (Integer) data.get("availableSlots"))
                .withData("runningJobs",        (long) (Integer) data.get("runningJobs"))
                .withData("queuedJobs",         (long) (Integer) data.get("queuedJobs"))
                .withData("maxConcurrentJobs",  (long) (Integer) data.get("maxConcurrentJobs"))
                .withData("maxQueueSize",       (long) (Integer) data.get("maxQueueSize"))
                .build();
    }
}
