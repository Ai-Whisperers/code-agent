package com.eneve.agent.tools;

import com.eneve.agent.model.CloudAccount;
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
 *   <li>Base credentials: the Quarkus-managed {@link StsClient} (configured via
 *       {@code quarkus.sts.*} properties) is used for the default credentials path — picks up
 *       the ECS task role in production or {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}
 *       env vars in local development.</li>
 *   <li>Cross-account access: when a {@code roleArn} is provided, STS {@code AssumeRole} is
 *       called to obtain temporary credentials scoped to the target customer account. This is the
 *       normal path in production — each customer account contains an {@code agent-readonly} role
 *       that trusts the code-agent's AWS account.</li>
 *   <li>Explicit credentials override: when account-specific or global {@code aws.access-key-id} /
 *       {@code aws.secret-access-key} config properties are present, a dedicated STS client is built
 *       with those credentials instead of the Quarkus-managed one.</li>
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

    /** Quarkus-managed STS client, configured via {@code quarkus.sts.*} properties. */
    @Inject
    StsClient stsClient;

    // ─── Public factory methods ───────────────────────────────────────────────────

    public CloudWatchLogsClient cloudWatchLogsClient(String roleArn, String region, CloudAccount account) {
        checkEnabled();
        return CloudWatchLogsClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region, account))
                .region(toRegion(region))
                .build();
    }

    public EcsClient ecsClient(String roleArn, String region, CloudAccount account) {
        checkEnabled();
        return EcsClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region, account))
                .region(toRegion(region))
                .build();
    }

    public CloudWatchClient cloudWatchClient(String roleArn, String region, CloudAccount account) {
        checkEnabled();
        return CloudWatchClient.builder()
                .credentialsProvider(resolveCredentials(roleArn, region, account))
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

    private AwsCredentialsProvider resolveCredentials(String roleArn, String region, CloudAccount account) {
        if (roleArn == null || roleArn.isBlank()) {
            LOG.debugf("No roleArn provided — using base credentials directly");
            return baseCredentials(account);
        }

        try {
            Region stsRegion = toRegion(region);

            // Use the Quarkus-managed STS client for the default credentials path (ECS task role /
            // env vars). When explicit per-account or global credentials are configured, build a
            // dedicated STS client with those credentials instead.
            StsClient effectiveSts = hasExplicitCredentials(account)
                    ? StsClient.builder()
                            .credentialsProvider(baseCredentials(account))
                            .region(stsRegion)
                            .build()
                    : stsClient;

            AssumeRoleResponse response = effectiveSts.assumeRole(AssumeRoleRequest.builder()
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

    /**
     * Returns {@code true} when explicit AWS credentials are available either from a linked
     * {@link CloudAccount} or from the global {@code aws.access-key-id} / {@code aws.secret-access-key}
     * config properties. When {@code false}, the Quarkus-managed {@link StsClient} (default
     * credentials — ECS task role or environment variables) is used instead.
     */
    private boolean hasExplicitCredentials(CloudAccount account) {
        if (account != null && account.credentials() != null) {
            String keyId = account.credentials().getOrDefault("awsKeyId", "").strip();
            String secret = account.credentials().getOrDefault("awsSecret", "").strip();
            if (!keyId.isBlank() && !secret.isBlank()) return true;
        }
        String keyId = settingsService.getSecret("aws.access-key-id").strip();
        String secret = settingsService.getSecret("aws.secret-access-key").strip();
        return !keyId.isBlank() && !secret.isBlank();
    }

    /**
     * Resolves the base {@link AwsCredentialsProvider} using the following priority:
     * <ol>
     *   <li>Explicit credentials from the linked {@link CloudAccount} ({@code awsKeyId} /
     *       {@code awsSecret} credential keys).</li>
     *   <li>Global config properties {@code aws.access-key-id} / {@code aws.secret-access-key}.</li>
     *   <li>{@link DefaultCredentialsProvider} — ECS task role or environment variables.</li>
     * </ol>
     */
    private AwsCredentialsProvider baseCredentials(CloudAccount account) {
        if (account != null && account.credentials() != null) {
            String keyId = account.credentials().getOrDefault("awsKeyId", "").strip();
            String secret = account.credentials().getOrDefault("awsSecret", "").strip();
            if (!keyId.isBlank() && !secret.isBlank()) {
                LOG.debugf("Using AWS credentials from cloud account '%s'", account.id());
                return StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret));
            }
        }
        String keyId = settingsService.getSecret("aws.access-key-id").strip();
        String secret = settingsService.getSecret("aws.secret-access-key").strip();
        if (!keyId.isBlank() && !secret.isBlank()) {
            LOG.debugf("Using explicit AWS credentials from config");
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret));
        }
        return DefaultCredentialsProvider.create();
    }
}
