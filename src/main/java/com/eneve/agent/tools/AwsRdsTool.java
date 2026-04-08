package com.eneve.agent.tools;

import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBClusterMember;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.Endpoint;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only Claude tool for inspecting AWS RDS resources and fetching database metrics.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code list_instances} — list all RDS DB instances (id, engine, status, class, multi-AZ)</li>
 *   <li>{@code describe_instance} — full details of one DB instance (endpoint, storage, backup retention, etc.)</li>
 *   <li>{@code list_clusters} — list Aurora DB clusters (id, engine, status, member instances)</li>
 *   <li>{@code describe_cluster} — full details of one Aurora cluster</li>
 *   <li>{@code get_instance_metrics} — CloudWatch {@code AWS/RDS} metrics for a DB instance over a time window</li>
 * </ul>
 */
@ApplicationScoped
public class AwsRdsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AwsRdsTool.class);
    private static final String RDS_NAMESPACE = "AWS/RDS";
    private static final int DEFAULT_PERIOD_SECONDS = 300;

    @Inject
    AwsClientFactory clientFactory;

    @Inject
    CustomerRegistryStore registryStore;

    @Inject
    CloudAccountStore cloudAccountStore;

    @Override
    public String name() {
        return "aws_rds";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String environmentName = (String) input.get("environmentName");
        String action = (String) input.get("action");

        if (action == null || action.isBlank()) {
            return "ERROR: 'action' is required (list_instances | describe_instance | "
                    + "list_clusters | describe_cluster | get_instance_metrics)";
        }

        try {
            AwsEnvConfig env = resolveEnv(customerId, environmentName);
            return switch (action.toLowerCase()) {
                case "list_instances" -> {
                    try (RdsClient client = clientFactory.rdsClient(env.roleArn(), env.region(), env.cloudAccount())) {
                        yield listInstances(client, env);
                    }
                }
                case "describe_instance" -> {
                    try (RdsClient client = clientFactory.rdsClient(env.roleArn(), env.region(), env.cloudAccount())) {
                        yield describeInstance(client, input, env);
                    }
                }
                case "list_clusters" -> {
                    try (RdsClient client = clientFactory.rdsClient(env.roleArn(), env.region(), env.cloudAccount())) {
                        yield listClusters(client, env);
                    }
                }
                case "describe_cluster" -> {
                    try (RdsClient client = clientFactory.rdsClient(env.roleArn(), env.region(), env.cloudAccount())) {
                        yield describeCluster(client, input, env);
                    }
                }
                case "get_instance_metrics" -> {
                    try (CloudWatchClient client = clientFactory.cloudWatchClient(env.roleArn(), env.region(), env.cloudAccount())) {
                        yield getInstanceMetrics(client, input, env);
                    }
                }
                default -> "ERROR: Unknown action '" + action + "'. Valid: list_instances, describe_instance, "
                        + "list_clusters, describe_cluster, get_instance_metrics";
            };
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            LOG.warnf("aws_rds failed for customer=%s env=%s action=%s: %s",
                    customerId, environmentName, action, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────────

    private String listInstances(RdsClient client, AwsEnvConfig env) {
        DescribeDbInstancesResponse response = client.describeDBInstances(
                DescribeDbInstancesRequest.builder().maxRecords(100).build());
        List<DBInstance> instances = response.dbInstances();

        StringBuilder sb = new StringBuilder();
        sb.append("RDS DB Instances [").append(env.label()).append("]\n");
        sb.append("Found ").append(instances.size()).append(" instance(s):\n\n");
        for (DBInstance i : instances) {
            sb.append("  ").append(i.dbInstanceIdentifier())
              .append("  engine=").append(i.engine()).append("/").append(i.engineVersion())
              .append("  class=").append(i.dbInstanceClass())
              .append("  status=").append(i.dbInstanceStatus())
              .append("  multiAZ=").append(i.multiAZ())
              .append("\n");
        }
        return sb.toString();
    }

    private String describeInstance(RdsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String dbInstanceId = requireParam(input, "dbInstanceId");
        DescribeDbInstancesResponse response = client.describeDBInstances(
                DescribeDbInstancesRequest.builder().dbInstanceIdentifier(dbInstanceId).build());
        List<DBInstance> instances = response.dbInstances();

        if (instances.isEmpty()) {
            return "No DB instance found with identifier: " + dbInstanceId;
        }
        DBInstance i = instances.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("RDS DB Instance [").append(env.label()).append("]\n");
        sb.append("Identifier:         ").append(i.dbInstanceIdentifier()).append("\n");
        sb.append("Status:             ").append(i.dbInstanceStatus()).append("\n");
        sb.append("Engine:             ").append(i.engine()).append(" ").append(i.engineVersion()).append("\n");
        sb.append("Instance class:     ").append(i.dbInstanceClass()).append("\n");
        sb.append("Multi-AZ:           ").append(i.multiAZ()).append("\n");
        sb.append("Storage type:       ").append(i.storageType()).append("\n");
        sb.append("Allocated storage:  ").append(i.allocatedStorage()).append(" GiB\n");
        if (i.maxAllocatedStorage() != null) {
            sb.append("Max storage:        ").append(i.maxAllocatedStorage()).append(" GiB\n");
        }
        sb.append("Storage encrypted:  ").append(i.storageEncrypted()).append("\n");
        sb.append("Backup retention:   ").append(i.backupRetentionPeriod()).append(" day(s)\n");
        sb.append("Deletion protection:").append(i.deletionProtection()).append("\n");
        sb.append("CA cert:            ").append(i.caCertificateIdentifier()).append("\n");
        if (i.availabilityZone() != null) {
            sb.append("AZ:                 ").append(i.availabilityZone()).append("\n");
        }
        Endpoint ep = i.endpoint();
        if (ep != null) {
            sb.append("Endpoint:           ").append(ep.address())
              .append(":").append(ep.port()).append("\n");
        }
        if (i.dbClusterIdentifier() != null && !i.dbClusterIdentifier().isBlank()) {
            sb.append("Cluster:            ").append(i.dbClusterIdentifier()).append("\n");
        }
        if (i.latestRestorableTime() != null) {
            sb.append("Latest restorable:  ").append(i.latestRestorableTime()).append("\n");
        }
        return sb.toString();
    }

    private String listClusters(RdsClient client, AwsEnvConfig env) {
        DescribeDbClustersResponse response = client.describeDBClusters(
                DescribeDbClustersRequest.builder().build());
        List<DBCluster> clusters = response.dbClusters();

        StringBuilder sb = new StringBuilder();
        sb.append("RDS DB Clusters [").append(env.label()).append("]\n");
        sb.append("Found ").append(clusters.size()).append(" cluster(s):\n\n");
        for (DBCluster c : clusters) {
            sb.append("  ").append(c.dbClusterIdentifier())
              .append("  engine=").append(c.engine()).append("/").append(c.engineVersion())
              .append("  status=").append(c.status())
              .append("  members=").append(c.dbClusterMembers().size())
              .append("\n");
        }
        return sb.toString();
    }

    private String describeCluster(RdsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String dbClusterId = requireParam(input, "dbClusterId");
        DescribeDbClustersResponse response = client.describeDBClusters(
                DescribeDbClustersRequest.builder().dbClusterIdentifier(dbClusterId).build());
        List<DBCluster> clusters = response.dbClusters();

        if (clusters.isEmpty()) {
            return "No DB cluster found with identifier: " + dbClusterId;
        }
        DBCluster c = clusters.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("RDS DB Cluster [").append(env.label()).append("]\n");
        sb.append("Identifier:         ").append(c.dbClusterIdentifier()).append("\n");
        sb.append("Status:             ").append(c.status()).append("\n");
        sb.append("Engine:             ").append(c.engine()).append(" ").append(c.engineVersion()).append("\n");
        sb.append("Multi-AZ:           ").append(c.multiAZ()).append("\n");
        sb.append("Storage encrypted:  ").append(c.storageEncrypted()).append("\n");
        sb.append("Backup retention:   ").append(c.backupRetentionPeriod()).append(" day(s)\n");
        sb.append("Deletion protection:").append(c.deletionProtection()).append("\n");
        if (c.endpoint() != null) {
            sb.append("Writer endpoint:    ").append(c.endpoint())
              .append(":").append(c.port()).append("\n");
        }
        if (c.readerEndpoint() != null) {
            sb.append("Reader endpoint:    ").append(c.readerEndpoint()).append("\n");
        }
        if (c.latestRestorableTime() != null) {
            sb.append("Latest restorable:  ").append(c.latestRestorableTime()).append("\n");
        }
        if (!c.dbClusterMembers().isEmpty()) {
            sb.append("\nCluster members:\n");
            for (DBClusterMember m : c.dbClusterMembers()) {
                sb.append("  ").append(m.dbInstanceIdentifier())
                  .append("  writer=").append(m.isClusterWriter())
                  .append("\n");
            }
        }
        return sb.toString();
    }

    private String getInstanceMetrics(CloudWatchClient client, Map<String, Object> input, AwsEnvConfig env) {
        String dbInstanceId = requireParam(input, "dbInstanceId");
        String metricName = requireParam(input, "metricName");

        String endTimeStr = (String) input.get("endTime");
        String startTimeStr = (String) input.get("startTime");
        Instant endTime = endTimeStr != null && !endTimeStr.isBlank()
                ? parseInstant(endTimeStr)
                : Instant.now();
        Instant startTime = startTimeStr != null && !startTimeStr.isBlank()
                ? parseInstant(startTimeStr)
                : endTime.minusSeconds(3600);

        int period = DEFAULT_PERIOD_SECONDS;
        Object periodObj = input.get("period");
        if (periodObj instanceof Number n) {
            period = Math.max(60, n.intValue());
        }

        String statStr = (String) input.get("stat");
        Statistic statistic = parseStatistic(statStr);

        GetMetricStatisticsResponse response = client.getMetricStatistics(
                GetMetricStatisticsRequest.builder()
                        .namespace(RDS_NAMESPACE)
                        .metricName(metricName)
                        .dimensions(Dimension.builder()
                                .name("DBInstanceIdentifier")
                                .value(dbInstanceId)
                                .build())
                        .startTime(startTime)
                        .endTime(endTime)
                        .period(period)
                        .statistics(statistic)
                        .build());

        List<Datapoint> datapoints = new ArrayList<>(response.datapoints());
        datapoints.sort(Comparator.comparing(Datapoint::timestamp));

        StringBuilder sb = new StringBuilder();
        sb.append("RDS Metrics [").append(env.label()).append("]\n");
        sb.append("Instance:   ").append(dbInstanceId).append("\n");
        sb.append("Metric:     ").append(metricName).append("\n");
        sb.append("Namespace:  ").append(RDS_NAMESPACE).append("\n");
        sb.append("Stat:       ").append(statistic.toString()).append("\n");
        sb.append("Period:     ").append(period).append("s\n");
        sb.append("From:       ").append(startTime).append("\n");
        sb.append("To:         ").append(endTime).append("\n\n");

        if (datapoints.isEmpty()) {
            sb.append("No data points returned for this time range.\n");
        } else {
            sb.append(String.format("%-30s  %s%n", "Timestamp", statistic));
            sb.append("-".repeat(55)).append("\n");
            for (Datapoint dp : datapoints) {
                sb.append(String.format("%-30s  %.4f%n", dp.timestamp(), statValue(dp, statistic)));
            }
        }
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private static double statValue(Datapoint dp, Statistic stat) {
        return switch (stat) {
            case AVERAGE -> dp.average() != null ? dp.average() : 0;
            case MAXIMUM -> dp.maximum() != null ? dp.maximum() : 0;
            case MINIMUM -> dp.minimum() != null ? dp.minimum() : 0;
            case SUM     -> dp.sum()     != null ? dp.sum()     : 0;
            default      -> dp.average() != null ? dp.average() : 0;
        };
    }

    private static Statistic parseStatistic(String stat) {
        if (stat == null || stat.isBlank()) return Statistic.AVERAGE;
        return switch (stat) {
            case "Maximum" -> Statistic.MAXIMUM;
            case "Minimum" -> Statistic.MINIMUM;
            case "Sum"     -> Statistic.SUM;
            default        -> Statistic.AVERAGE;
        };
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp: " + value);
        }
    }

    private static String requireParam(Map<String, Object> input, String key) {
        String value = (String) input.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required for this action");
        }
        return value;
    }

    private AwsEnvConfig resolveEnv(String customerId, String environmentName) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("'customerId' is required");
        }
        if (environmentName == null || environmentName.isBlank()) {
            throw new IllegalArgumentException("'environmentName' is required (e.g. 'production', 'acceptance')");
        }
        Optional<CustomerConfig> customer = registryStore.getCustomer(customerId);
        if (customer.isEmpty()) {
            throw new IllegalArgumentException("Customer '" + customerId + "' not found in registry");
        }
        CustomerConfig cfg = customer.get();
        if (cfg.environments() == null) {
            throw new IllegalArgumentException("Customer '" + customerId + "' has no environments configured");
        }
        EnvironmentConfig env = cfg.environments().stream()
                .filter(e -> matchesEnvironment(e, environmentName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Environment '" + environmentName + "' not found for customer '" + customerId + "'. "
                        + "Available: " + cfg.environments().stream()
                                .map(AwsRdsTool::effectiveEnvName).toList()));

        if (env.aws() == null) {
            throw new IllegalArgumentException(
                    "Environment '" + environmentName + "' of customer '" + customerId + "' has no AWS config");
        }
        String roleArn = env.aws().iamRole() != null ? env.aws().iamRole() : "";
        String region = env.aws().region() != null ? env.aws().region() : "";
        String label = customerId + "/" + environmentName + " (" + env.aws().accountId() + ")";

        CloudAccount cloudAccount = cfg.cloudAccountId() != null && !cfg.cloudAccountId().isBlank()
                ? cloudAccountStore.getCloudAccountUnmasked(cfg.cloudAccountId()).orElse(null)
                : null;

        return new AwsEnvConfig(roleArn, region, label, cloudAccount);
    }

    record AwsEnvConfig(String roleArn, String region, String label, CloudAccount cloudAccount) {}

    /**
     * Returns the effective display name for an environment.
     * Falls back to {@code type} when {@code name} is null or blank (common for legacy data).
     */
    static String effectiveEnvName(EnvironmentConfig e) {
        return (e.name() != null && !e.name().isBlank()) ? e.name() : e.type();
    }

    /**
     * Matches an environment by name (case-insensitive), falling back to type when name is blank.
     * This handles environments that were stored without an explicit name field.
     */
    private static boolean matchesEnvironment(EnvironmentConfig e, String environmentName) {
        return environmentName.equalsIgnoreCase(effectiveEnvName(e));
    }
}
