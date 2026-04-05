package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.QaTestPlanConversionRequest;
import com.eneve.agent.qa.QaTestPlanService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles {@link JobType#QA_TESTPLAN_CONVERSION} jobs.
 * Delegates to {@link QaTestPlanService#generateJson} and archives the result.
 */
@ApplicationScoped
public class QaTestPlanConversionHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(QaTestPlanConversionHandler.class);

    @Inject QaTestPlanService service;
    @Inject JobStore jobStore;

    @Override
    public JobType jobType() {
        return JobType.QA_TESTPLAN_CONVERSION;
    }

    @Override
    public void handle(JobRecord job) {
        QaTestPlanConversionRequest request = (QaTestPlanConversionRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("QaTestPlanConversion job %s starting: feature=%s", job.getJobId(), request.issueKey());

        try {
            var plan = service.generateJson(request.issueKey(), job.getJobId());
            String summary = "Test plan JSON generated for " + request.issueKey();
            if (plan.kpiBehaviourTcCount() != null || plan.kpiCapabilityTcCount() != null) {
                int b = plan.kpiBehaviourTcCount() != null ? plan.kpiBehaviourTcCount() : 0;
                int c = plan.kpiCapabilityTcCount() != null ? plan.kpiCapabilityTcCount() : 0;
                summary += " — " + b + " behaviour TCs, " + c + " capability TCs";
            }
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("QaTestPlanConversion job %s succeeded for %s", job.getJobId(), request.issueKey());
        } catch (Exception e) {
            LOG.errorf("QaTestPlanConversion job %s failed for %s: %s",
                    job.getJobId(), request.issueKey(), e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobStore.archive(job);
        }
    }
}
