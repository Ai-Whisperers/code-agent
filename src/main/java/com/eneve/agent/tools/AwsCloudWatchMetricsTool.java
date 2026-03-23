package com.eneve.agent.tools;

import com.eneve.agent.agent.store.CustomerRegistryStore;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only Claude tool for querying AWS CloudWatch Metrics for ECS services.
 *
 * <p>Uses {@code GetMetricStatistics} against the {@code AWS/ECS} namespace to retrieve
 * CPU utilisation, memory utilisation or other ECS-level metrics over a time window.
 * Results are returned as a sorted timestamp→value table.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>CPU / memory trend for a specific ECS service over the last hour</li>
 *   <li>Spot CPU spikes that correlate with an application incident</li>
 *   <li>Compare resource utilisation between acceptance and production</li>
 * </ul>
 */
@ApplicationScoped
public class AwsCloudWatchMetricsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AwsCloudWatchMetricsTool.class);
    private static final String ECS_NAMESPACE = "AWS/ECS";
    private static final int DEFAULT_PERIOD_SECONDS = 300;

    @Inject
    AwsClientFactory clientFactory;

    @Inject
    CustomerRegistryStore registryStore;

    @Override
    public String name() {
        return "aws_cloudwatch_metrics";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String environmentName = (String) input.get("environmentName");
        String metricName = (String) input.get("metricName");

        if (metricName == null || metricName.isBlank()) {
            return "ERROR: 'metricName' is required (e.g. CPUUtilization, MemoryUtilization)";
        }

        String clusterName = (String) input.get("clusterName");
        String serviceName = (String) input.get("serviceName");

        String startTimeStr = (String) input.get("startTime");
        String endTimeStr = (String) input.get("endTime");

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

        try {
            AwsEnvConfig env = resolveEnv(customerId, environmentName);
            try (CloudWatchClient client = clientFactory.cloudWatchClient(env.roleArn(), env.region())) {
                return getMetrics(client, metricName, clusterName, serviceName,
                        startTime, endTime, period, statistic, env);
            }
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            LOG.warnf("aws_cloudwatch_metrics failed for customer=%s env=%s metric=%s: %s",
                    customerId, environmentName, metricName, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── Core query ───────────────────────────────────────────────────────────────

    private String getMetrics(CloudWatchClient client, String metricName,
                               String clusterName, String serviceName,
                               Instant startTime, Instant endTime,
                               int period, Statistic statistic, AwsEnvConfig env) {

        List<Dimension> dimensions = new ArrayList<>();
        if (clusterName != null && !clusterName.isBlank()) {
            dimensions.add(Dimension.builder().name("ClusterName").value(clusterName).build());
        }
        if (serviceName != null && !serviceName.isBlank()) {
            dimensions.add(Dimension.builder().name("ServiceName").value(serviceName).build());
        }

        GetMetricStatisticsRequest.Builder req = GetMetricStatisticsRequest.builder()
                .namespace(ECS_NAMESPACE)
                .metricName(metricName)
                .startTime(startTime)
                .endTime(endTime)
                .period(period)
                .statistics(statistic);

        if (!dimensions.isEmpty()) {
            req.dimensions(dimensions);
        }

        GetMetricStatisticsResponse resp = client.getMetricStatistics(req.build());
        List<Datapoint> datapoints = new ArrayList<>(resp.datapoints());
        datapoints.sort(Comparator.comparing(Datapoint::timestamp));

        StringBuilder sb = new StringBuilder();
        sb.append("CloudWatch Metrics [").append(env.label()).append("]\n");
        sb.append("Metric:    ").append(ECS_NAMESPACE).append("/").append(metricName).append("\n");
        sb.append("Statistic: ").append(statistic.toString()).append("\n");
        sb.append("Period:    ").append(period).append("s\n");
        sb.append("Range:     ").append(startTime).append(" → ").append(endTime).append("\n");
        if (clusterName != null) sb.append("Cluster:   ").append(clusterName).append("\n");
        if (serviceName != null) sb.append("Service:   ").append(serviceName).append("\n");
        sb.append("\n");

        if (datapoints.isEmpty()) {
            sb.append("No datapoints found for the given time range and dimensions.\n");
            sb.append("Tip: verify the clusterName and serviceName match exactly what ECS reports.\n");
        } else {
            sb.append(String.format("%-32s  %s%n", "Timestamp (UTC)", metricName + " (%)"));
            sb.append("-".repeat(52)).append("\n");
            for (Datapoint dp : datapoints) {
                double value = statValue(dp, statistic);
                sb.append(String.format("%-32s  %.2f%n", dp.timestamp().toString(), value));
            }
            sb.append("\n");

            double min = datapoints.stream().mapToDouble(dp -> statValue(dp, statistic)).min().orElse(0);
            double max = datapoints.stream().mapToDouble(dp -> statValue(dp, statistic)).max().orElse(0);
            double avg = datapoints.stream().mapToDouble(dp -> statValue(dp, statistic)).average().orElse(0);
            sb.append(String.format("Summary: min=%.2f%%  avg=%.2f%%  max=%.2f%%%n", min, avg, max));
        }
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private static double statValue(Datapoint dp, Statistic statistic) {
        return switch (statistic) {
            case AVERAGE -> dp.average() != null ? dp.average() : 0;
            case MAXIMUM -> dp.maximum() != null ? dp.maximum() : 0;
            case MINIMUM -> dp.minimum() != null ? dp.minimum() : 0;
            case SUM     -> dp.sum() != null ? dp.sum() : 0;
            case SAMPLE_COUNT -> dp.sampleCount() != null ? dp.sampleCount() : 0;
            default -> dp.average() != null ? dp.average() : 0;
        };
    }

    private static Statistic parseStatistic(String stat) {
        if (stat == null || stat.isBlank()) return Statistic.AVERAGE;
        return switch (stat.toUpperCase()) {
            case "MAXIMUM", "MAX" -> Statistic.MAXIMUM;
            case "MINIMUM", "MIN" -> Statistic.MINIMUM;
            case "SUM"            -> Statistic.SUM;
            case "SAMPLECOUNT"    -> Statistic.SAMPLE_COUNT;
            default               -> Statistic.AVERAGE;
        };
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Cannot parse time value '" + value
                        + "'. Use ISO-8601 (e.g. 2025-01-01T00:00:00Z) or epoch milliseconds.");
            }
        }
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
        if (customer.get().environments() == null) {
            throw new IllegalArgumentException("Customer '" + customerId + "' has no environments configured");
        }
        EnvironmentConfig env = customer.get().environments().stream()
                .filter(e -> environmentName.equalsIgnoreCase(e.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Environment '" + environmentName + "' not found for customer '" + customerId + "'"));

        if (env.aws() == null) {
            throw new IllegalArgumentException(
                    "Environment '" + environmentName + "' of customer '" + customerId + "' has no AWS config");
        }
        String roleArn = env.aws().iamRole() != null ? env.aws().iamRole() : "";
        String region = env.aws().region() != null ? env.aws().region() : "";
        String label = customerId + "/" + environmentName + " (" + env.aws().accountId() + ")";
        return new AwsEnvConfig(roleArn, region, label);
    }

    record AwsEnvConfig(String roleArn, String region, String label) {}
}
