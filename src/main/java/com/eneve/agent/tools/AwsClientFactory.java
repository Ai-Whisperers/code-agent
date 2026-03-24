package com.eneve.agent.tools;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Factory that creates short-lived AWS SDK v2 clients scoped to a specific customer account.
 *
 * <p>Authentication strategy:
 * <ol>
 *   <li>Base credentials: {@link DefaultCredentialsProvider} — picks up the ECS task role in
 *       production, or {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} env vars in
 *       local development (set via {@code aws.access-key-id} / {@code aws.secret-access-key}
 *       config properties).</li>
 *   <li>Cross-account access: when a {@code roleArn} is provided, STS {@code AssumeRole} is
 *       called to obtain temporary credentials scoped to the target customer account. This is the
 *       normal path in production — each customer account contains an {@code agent-readonly} role
 *       that trusts the code-agent's AWS account.</li>
 *   <li>Local dev fallback: when {@code roleArn} is blank the base credentials are used directly,
 *       which is useful when the developer's local AWS profile already has access to the target
 *       account.</li>
 * </ol>
 *
 * <p>Required IAM permissions:
 * <pre>
 * Code-agent task role (your account):
 *   sts:AssumeRole  on  arn:aws:iam::*:role/agent-readonly
 *
 * agent-readonly role (each customer account, trust policy allows code-agent account):
 *   logs:DescribeLogGroups, logs:DescribeLogStreams, logs:FilterLogEvents
 *   ecs:ListClusters, ecs:DescribeClusters, ecs:ListServices, ecs:DescribeServices,
 *   ecs:ListTasks, ecs:DescribeTasks, ecs:DescribeTaskDefinition
 *   cloudwatch:GetMetricStatistics, cloudwatch:GetMetricData
 * </pre>
 */
@ApplicationScoped
public class AwsClientFactory {

    private static final Logger LOG = Logger.getLogger(AwsClientFactory.class);
    private static final String SESSION_NAME = "code-agent-readonly";
    private static final int SESSION_DURATION_SECONDS = 3600;

    @Inject
    SettingsService settingsService;

    // ─── Public factory methods ───────────────────────────────────────────────────

    public CloudWatchLogsClient cloudWatchLogsClient(String roleArn, String region) {
        checkEnabled();
        return CloudWatchLogsClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region))
                .region(toRegion(region))
                .build();
    }

    public EcsClient ecsClient(String roleArn, String region) {
        checkEnabled();
        return EcsClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region))
                .region(toRegion(region))
                .build();
    }

    public CloudWatchClient cloudWatchClient(String roleArn, String region) {
        checkEnabled();
        return CloudWatchClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region))
                .region(toRegion(region))
                .build();
    }

    // ─── Internal ─────────────────────────────────────────────────────────────────

    private void checkEnabled() {
        boolean enabled = Boolean.parseBoolean(settingsService.get("tools.aws.enabled", "true"));
        if (!enabled) {
            throw new IllegalStateException("AWS tools are disabled. Set tools.aws.enabled=true to enable.");
        }
    }

    private Region toRegion(String region) {
        String defaultRegion = settingsService.get("aws.region", "eu-central-1");
        return (region != null && !region.isBlank()) ? Region.of(region) : Region.of(defaultRegion);
    }

    private AwsCredentialsProvider resolveCredentials(String roleArn, String region) {
        AwsCredentialsProvider base = baseCredentials();

        if (roleArn == null || roleArn.isBlank()) {
            LOG.debugf("No roleArn provided — using base credentials directly");
            return base;
        }

        try {
            Region stsRegion = toRegion(region);
            StsClient sts = StsClient.builder()
                    .credentialsProvider(base)
                    .region(stsRegion)
                    .build();

            AssumeRoleResponse response = sts.assumeRole(AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName(SESSION_NAME)
                    .durationSeconds(SESSION_DURATION_SECONDS)
                    .build());

            Credentials c = response.credentials();
            LOG.debugf("Assumed role %s — session expires %s", roleArn, c.expiration());

            return StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(
                            c.accessKeyId(),
                            c.secretAccessKey(),
                            c.sessionToken()
                    )
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to assume role " + roleArn + ": " + e.getMessage(), e);
        }
    }

    private AwsCredentialsProvider baseCredentials() {
        String keyId = settingsService.getSecret("aws.access-key-id").strip();
        String secret = settingsService.getSecret("aws.secret-access-key").strip();
        if (!keyId.isBlank() && !secret.isBlank()) {
            LOG.debugf("Using explicit AWS credentials from config");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret));
        }
        return DefaultCredentialsProvider.create();
    }
}
