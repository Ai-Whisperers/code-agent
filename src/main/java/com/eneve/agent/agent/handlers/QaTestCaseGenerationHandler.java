package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.QaTestCaseGenerationRequest;
import com.eneve.agent.qa.QaTestCaseService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles {@link JobType#QA_TESTCASE_GENERATION} jobs.
 * Delegates to {@link QaTestCaseService#generateForPlan} and archives the result.
 */
@ApplicationScoped
public class QaTestCaseGenerationHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(QaTestCaseGenerationHandler.class);

    @Inject QaTestCaseService service;
    @Inject JobStore jobStore;

    @Override
    public JobType jobType() {
        return JobType.QA_TESTCASE_GENERATION;
    }

    @Override
    public void handle(JobRecord job) {
        QaTestCaseGenerationRequest request = (QaTestCaseGenerationRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("QaTestCaseGeneration job %s starting: planId=%s feature=%s",
                job.getJobId(), request.planId(), request.issueKey());

        try {
            QaTestCaseService.GenerationResult result = service.generateForPlan(request.planId(), job.getJobId());
            String summary = "Generated " + result.totalCases() + " test cases across "
                    + result.storiesProcessed() + " stories for " + request.issueKey();
            if (result.storiesFailed() > 0) {
                summary += " (" + result.storiesFailed() + " stories failed — partial result)";
            }
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("QaTestCaseGeneration job %s succeeded: %s", job.getJobId(), summary);
        } catch (Exception e) {
            LOG.errorf("QaTestCaseGeneration job %s failed for planId=%s: %s",
                    job.getJobId(), request.planId(), e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobStore.archive(job);
        }
    }
}
