package com.eneve.agent.servicedesk;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ServiceDeskTriageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Dispatches {@link JobType#SERVICE_DESK_TRIAGE} jobs to {@link ServiceDeskTriageService}.
 *
 * <p>Registered automatically via CDI {@code Instance<JobHandler>} in {@code AgentRunner}.
 * No manual wiring is required.
 */
@ApplicationScoped
public class ServiceDeskTriageHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ServiceDeskTriageHandler.class);

    @Inject ServiceDeskTriageService triageService;
    @Inject JobStore jobStore;

    @Override
    public JobType jobType() {
        return JobType.SERVICE_DESK_TRIAGE;
    }

    @Override
    public void handle(JobRecord job) {
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        if (!(job.getPayload() instanceof ServiceDeskTriageRequest request)) {
            LOG.errorf("ServiceDeskTriageHandler: job %s has unexpected payload type — failing",
                    job.getJobId());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Unexpected payload type: " + job.getPayload());
            jobStore.archive(job);
            return;
        }

        LOG.infof("ServiceDeskTriageHandler: starting triage job %s for issue %s",
                job.getJobId(), request.issueKey());

        try {
            triageService.triage(request);
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Service desk triage completed for " + request.issueKey());
            jobStore.archive(job);
            LOG.infof("ServiceDeskTriageHandler: job %s completed for %s",
                    job.getJobId(), request.issueKey());
        } catch (Exception e) {
            LOG.errorf("ServiceDeskTriageHandler: job %s failed for %s: %s",
                    job.getJobId(), request.issueKey(), e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Triage failed: " + e.getMessage());
            jobStore.archive(job);
        }
    }
}
