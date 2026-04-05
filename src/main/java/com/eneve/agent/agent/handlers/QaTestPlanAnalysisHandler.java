package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.QaTestPlanAnalysisRequest;
import com.eneve.agent.qa.QaTestPlanService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles {@link JobType#QA_TESTPLAN_ANALYSIS} jobs.
 * Delegates to {@link QaTestPlanService#generateAnalysis} and archives the result.
 */
@ApplicationScoped
public class QaTestPlanAnalysisHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(QaTestPlanAnalysisHandler.class);

    @Inject QaTestPlanService service;
    @Inject JobStore jobStore;

    @Override
    public JobType jobType() {
        return JobType.QA_TESTPLAN_ANALYSIS;
    }

    @Override
    public void handle(JobRecord job) {
        QaTestPlanAnalysisRequest request = (QaTestPlanAnalysisRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("QaTestPlanAnalysis job %s starting: scope=%s feature=%s",
                job.getJobId(), request.scopeId(), request.issueKey());

        try {
            var plan = service.generateAnalysis(request.scopeId(), request.issueKey(), job.getJobId());
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Analysis generated for " + request.issueKey()
                    + (plan.analysisEdited() ? " (edited)" : ""));
            jobStore.archive(job);
            LOG.infof("QaTestPlanAnalysis job %s succeeded for %s", job.getJobId(), request.issueKey());
        } catch (Exception e) {
            LOG.errorf("QaTestPlanAnalysis job %s failed for %s: %s",
                    job.getJobId(), request.issueKey(), e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobStore.archive(job);
        }
    }
}
