package com.eneve.agent.tools;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only Claude tool for querying AWS CloudWatch Logs.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code list_groups} — list log groups (optionally filtered by name prefix)</li>
 *   <li>{@code list_streams} — list log streams in a log group</li>
 *   <li>{@code filter_events} — filter log events by pattern and/or time range</li>
 * </ul>
 *
 * <p>Cross-account access is handled transparently via {@link AwsClientFactory}: the tool
 * resolves the IAM role ARN from the customer's {@code EnvironmentConfig.aws.iamRole} and calls
 * STS {@code AssumeRole} to get short-lived credentials for the customer's account.
 */
@ApplicationScoped
public class AwsCloudWatchLogsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AwsCloudWatchLogsTool.class);
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    @Inject
    AwsClientFactory clientFactory;

    @Inject
    CustomerRegistryStore registryStore;

    @Override
    public String name() {
        return "aws_cloudwatch_logs";
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
            return "ERROR: 'action' is required (list_groups | list_streams | filter_events)";
        }

        try {
            AwsEnvConfig env = resolveEnv(customerId, environmentName);
            try (CloudWatchLogsClient client = clientFactory.cloudWatchLogsClient(env.roleArn(), env.region())) {
                return switch (action.toLowerCase()) {
                    case "list_groups" -> listGroups(client, input, env);
                    case "list_streams" -> listStreams(client, input, env);
                    case "filter_events" -> filterEvents(client, input, env);
                    default -> "ERROR: Unknown action '" + action + "'. Valid actions: list_groups, list_streams, filter_events";
                };
            }
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            LOG.warnf("aws_cloudwatch_logs failed for customer=%s env=%s action=%s: %s",
                    customerId, environmentName, action, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────────

    private String listGroups(CloudWatchLogsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String prefix = (String) input.get("logGroupName");
        DescribeLogGroupsRequest.Builder req = DescribeLogGroupsRequest.builder().limit(50);
        if (prefix != null && !prefix.isBlank()) {
            req.logGroupNamePrefix(prefix);
        }
        DescribeLogGroupsResponse resp = client.describeLogGroups(req.build());

        StringBuilder sb = new StringBuilder();
        sb.append("CloudWatch Log Groups [").append(env.label()).append("]\n");
        sb.append("Found ").append(resp.logGroups().size()).append(" group(s):\n\n");
        resp.logGroups().forEach(g -> {
            sb.append("  ").append(g.logGroupName());
            if (g.retentionInDays() != null) {
                sb.append("  (retention: ").append(g.retentionInDays()).append(" days)");
            }
            if (g.storedBytes() != null) {
                sb.append("  [").append(humanBytes(g.storedBytes())).append("]");
            }
            sb.append("\n");
        });
        if (resp.nextToken() != null) {
            sb.append("\n... more groups available (results truncated at 50)\n");
        }
        return sb.toString();
    }

    private String listStreams(CloudWatchLogsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String logGroupName = (String) input.get("logGroupName");
        if (logGroupName == null || logGroupName.isBlank()) {
            return "ERROR: 'logGroupName' is required for action=list_streams";
        }
        DescribeLogStreamsResponse resp = client.describeLogStreams(
                DescribeLogStreamsRequest.builder()
                        .logGroupName(logGroupName)
                        .orderBy("LastEventTime")
                        .descending(true)
                        .limit(20)
                        .build());

        StringBuilder sb = new StringBuilder();
        sb.append("Log Streams in '").append(logGroupName).append("' [").append(env.label()).append("]\n");
        sb.append("Found ").append(resp.logStreams().size()).append(" stream(s) (most recent first):\n\n");
        resp.logStreams().forEach(s -> {
            sb.append("  ").append(s.logStreamName());
            if (s.lastEventTimestamp() != null) {
                sb.append("  last event: ").append(Instant.ofEpochMilli(s.lastEventTimestamp()));
            }
            sb.append("\n");
        });
        return sb.toString();
    }

    private String filterEvents(CloudWatchLogsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String logGroupName = (String) input.get("logGroupName");
        if (logGroupName == null || logGroupName.isBlank()) {
            return "ERROR: 'logGroupName' is required for action=filter_events";
        }

        int limit = DEFAULT_LIMIT;
        Object limitObj = input.get("limit");
        if (limitObj instanceof Number n) {
            limit = Math.min(Math.max(n.intValue(), 1), MAX_LIMIT);
        }

        FilterLogEventsRequest.Builder req = FilterLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .limit(limit);

        String filterPattern = (String) input.get("filterPattern");
        if (filterPattern != null && !filterPattern.isBlank()) {
            req.filterPattern(filterPattern);
        }

        String startTime = (String) input.get("startTime");
        String endTime = (String) input.get("endTime");
        if (startTime != null && !startTime.isBlank()) {
            req.startTime(parseEpochMillis(startTime));
        }
        if (endTime != null && !endTime.isBlank()) {
            req.endTime(parseEpochMillis(endTime));
        }

        String logStreamName = (String) input.get("logStreamName");
        if (logStreamName != null && !logStreamName.isBlank()) {
            req.logStreamNames(java.util.List.of(logStreamName));
        }

        FilterLogEventsResponse resp = client.filterLogEvents(req.build());

        StringBuilder sb = new StringBuilder();
        sb.append("CloudWatch Log Events [").append(env.label()).append("]\n");
        sb.append("Log group: ").append(logGroupName);
        if (filterPattern != null && !filterPattern.isBlank()) {
            sb.append("  filter: ").append(filterPattern);
        }
        sb.append("\nFound ").append(resp.events().size()).append(" event(s):\n\n");

        for (FilteredLogEvent event : resp.events()) {
            String line = Instant.ofEpochMilli(event.timestamp()) + "  [" + event.logStreamName() + "]  " + event.message();
            sb.append(line).append("\n");
            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("\n... [output truncated at ").append(MAX_OUTPUT_CHARS).append(" characters]\n");
                break;
            }
        }
        if (resp.events().isEmpty()) {
            sb.append("No events matched the query.\n");
        }
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

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

    private static long parseEpochMillis(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Cannot parse time value '" + value
                        + "'. Use ISO-8601 (e.g. 2025-01-01T00:00:00Z) or epoch milliseconds.");
            }
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

    record AwsEnvConfig(String roleArn, String region, String label) {}
}
