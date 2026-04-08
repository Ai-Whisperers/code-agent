package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.architecture.ArchitectureDiagramDto;
import com.eneve.agent.architecture.ArchitectureDiagramStore;
import com.eneve.agent.architecture.StructurizrService;
import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.aws.AwsDiscoveryResult;
import com.eneve.agent.aws.AwsResourceDiscoverer;
import com.eneve.agent.aws.CloudArchitectureDslBuilder;
import com.eneve.agent.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Handles {@link JobType#GENERATE_CLOUD_ARCHITECTURE} jobs.
 *
 * <p>Orchestrates AWS resource discovery ({@link AwsResourceDiscoverer}),
 * Structurizr DSL generation ({@link CloudArchitectureDslBuilder}),
 * and diagram persistence.
 */
@ApplicationScoped
public class GenerateCloudArchitectureHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateCloudArchitectureHandler.class);

    @Inject AwsResourceDiscoverer discoverer;
    @Inject CloudArchitectureDslBuilder dslBuilder;
    @Inject CustomerRegistryStore registryStore;
    @Inject CloudAccountStore cloudAccountStore;
    @Inject StructurizrService structurizrService;
    @Inject ArchitectureDiagramStore diagramStore;
    @Inject JobStore jobStore;

    @Override
    public JobType jobType() {
        return JobType.GENERATE_CLOUD_ARCHITECTURE;
    }

    @Override
    public void handle(JobRecord job) {
        GenerateCloudArchitectureRequest request = (GenerateCloudArchitectureRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("GenerateCloudArchitecture job %s starting for customer=%s env=%s",
                job.getJobId(), request.customerId(), request.environmentName());

        Optional<CustomerConfig> customerOpt = registryStore.getCustomer(request.customerId());
        if (customerOpt.isEmpty()) {
            failJob(job, "Customer '" + request.customerId() + "' not found in registry");
            return;
        }
        CustomerConfig customer = customerOpt.get();

        if (customer.environments() == null || customer.environments().isEmpty()) {
            failJob(job, "Customer '" + request.customerId() + "' has no environments configured");
            return;
        }

        EnvironmentConfig env = customer.environments().stream()
                .filter(e -> request.environmentName().equalsIgnoreCase(effectiveEnvName(e)))
                .findFirst().orElse(null);

        if (env == null) {
            failJob(job, "Environment '" + request.environmentName() + "' not found for customer '"
                    + request.customerId() + "'");
            return;
        }
        if (env.aws() == null) {
            failJob(job, "Environment '" + request.environmentName() + "' has no AWS config");
            return;
        }

        String roleArn = env.aws().iamRole()  != null ? env.aws().iamRole()  : "";
        String region  = env.aws().region()   != null ? env.aws().region()   : "";
        CloudAccount cloudAccount = customer.cloudAccountId() != null && !customer.cloudAccountId().isBlank()
                ? cloudAccountStore.getCloudAccountUnmasked(customer.cloudAccountId()).orElse(null)
                : null;

        AwsDiscoveryResult discovery = discoverer.discover(roleArn, region, cloudAccount);

        if (discovery.isEmpty()) {
            failJob(job, "No AWS resources discovered — check credentials and IAM permissions");
            return;
        }

        Optional<String> pinnedDsl = diagramStore.findPinnedCloudDsl(
                request.customerId(), request.environmentName());

        String dslContent = dslBuilder.build(customer.name(), request.environmentName(), region,
                discovery, pinnedDsl.orElse(""));

        LOG.debugf("GenerateCloudArchitecture: generated DSL for customer=%s env=%s:%n%s",
                request.customerId(), request.environmentName(), dslContent);

        List<ArchitectureDiagramDto> diagrams;
        try {
            diagrams = structurizrService.validateAndExport(dslContent);
        } catch (Exception e) {
            LOG.errorf("DSL validation failed for customer=%s env=%s. DSL was:%n%s",
                    request.customerId(), request.environmentName(), dslContent);
            failJob(job, "DSL validation failed: " + e.getMessage());
            return;
        }

        if (diagrams.isEmpty()) {
            failJob(job, "DSL parsed but no views were generated");
            return;
        }

        for (ArchitectureDiagramDto diagram : diagrams) {
            diagramStore.insertCloudVersion(
                    request.customerId(), request.environmentName(),
                    diagram.viewName(), diagram.viewType(),
                    "ai", dslContent, diagram.mermaidSrc());
        }

        LOG.infof("GenerateCloudArchitecture: persisted %d view(s) for customer=%s env=%s",
                diagrams.size(), request.customerId(), request.environmentName());

        job.setStatus(JobStatus.SUCCESS);
        job.setSummary(String.format(
                "Discovered: %d CloudFront distribution(s), %d VPC(s), %d EC2 instance(s), " +
                "%d ECS cluster(s) (%d service(s)), %d RDS instance(s), %d load balancer(s), " +
                "%d ElastiCache cluster(s), %d Lambda function(s), %d S3 bucket(s). Generated %d diagram view(s).",
                discovery.cfDistributions.size(), discovery.vpcs.size(), discovery.instances.size(),
                discovery.ecsClusters.size(),
                discovery.ecsClusters.stream().mapToInt(c -> c.services().size()).sum(),
                discovery.rdsInstances.size(), discovery.loadBalancers.size(),
                discovery.cacheClusters.size(), discovery.lambdaFunctions.size(),
                discovery.s3Buckets.size(), diagrams.size()));
        jobStore.archive(job);
    }

    private void failJob(JobRecord job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(reason);
        jobStore.update(job);
        LOG.errorf("GenerateCloudArchitecture job %s failed: %s", job.getJobId(), reason);
    }

    private static String effectiveEnvName(EnvironmentConfig e) {
        return (e.name() != null && !e.name().isBlank()) ? e.name() : e.type();
    }
}
