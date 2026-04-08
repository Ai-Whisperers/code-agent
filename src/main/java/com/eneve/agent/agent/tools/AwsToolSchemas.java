package com.eneve.agent.agent.tools;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

public final class AwsToolSchemas {

    private AwsToolSchemas() { }

    public static Tool awsCloudWatchLogs() {
        return Tool.builder()
                .name("aws_cloudwatch_logs")
                .description("Query AWS CloudWatch Logs for a customer environment. "
                        + "Use this to fetch application logs, search for errors or exceptions, "
                        + "or tail log streams from ECS Fargate containers. "
                        + "Cross-account access is handled automatically via IAM role assumption. "
                        + "Actions: list_groups (list log groups), list_streams (list streams in a group), "
                        + "filter_events (filter log events by pattern and/or time range).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_groups", "list_streams", "filter_events"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("logGroupName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Log group name or prefix. Required for list_streams and filter_events."
                                )))
                                .putAdditionalProperty("logStreamName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Restrict filter_events to a specific log stream (optional)"
                                )))
                                .putAdditionalProperty("filterPattern", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch filter pattern, e.g. 'ERROR' or '?Exception ?Error'"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (e.g. 2025-01-01T00:00:00Z)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("limit", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of log events to return (default: 100, max: 500)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }

    public static Tool awsEcs() {
        return Tool.builder()
                .name("aws_ecs")
                .description("Inspect AWS ECS / Fargate resources for a customer environment. "
                        + "Use this to check container health, service status, task definitions, "
                        + "and diagnose deployment or configuration issues. "
                        + "Cross-account access is handled automatically via IAM role assumption. "
                        + "Actions: list_clusters, describe_cluster, list_services, describe_service "
                        + "(shows desired/running/pending counts and deployment status), "
                        + "list_tasks (use desiredStatus=STOPPED to find failed tasks), "
                        + "describe_task (shows container exit codes and stop reasons), "
                        + "describe_task_definition (shows image, CPU/memory, env var count).")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_clusters", "describe_cluster", "list_services",
                                                "describe_service", "list_tasks", "describe_task",
                                                "describe_task_definition"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("clusterArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS cluster ARN or name. Required for most actions."
                                )))
                                .putAdditionalProperty("serviceArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS service ARN or name. Required for describe_service; optional filter for list_tasks."
                                )))
                                .putAdditionalProperty("taskArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS task ARN. Required for describe_task."
                                )))
                                .putAdditionalProperty("taskDefinitionArn", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Task definition ARN or family:revision. Required for describe_task_definition."
                                )))
                                .putAdditionalProperty("desiredStatus", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("RUNNING", "STOPPED"),
                                        "description", "Filter list_tasks by desired status. Use STOPPED to find failed tasks."
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }

    public static Tool awsCloudWatchMetrics() {
        return Tool.builder()
                .name("aws_cloudwatch_metrics")
                .description("Query AWS CloudWatch Metrics for ECS service resource utilisation. "
                        + "Use this to retrieve CPU or memory utilisation trends for an ECS service "
                        + "over a time window — useful for spotting spikes, capacity issues, or "
                        + "comparing acceptance vs production resource usage. "
                        + "Cross-account access is handled automatically via IAM role assumption.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("metricName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch metric name, e.g. CPUUtilization or MemoryUtilization"
                                )))
                                .putAdditionalProperty("clusterName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS cluster name (used as dimension filter)"
                                )))
                                .putAdditionalProperty("serviceName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "ECS service name (used as dimension filter)"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (defaults to 1 hour ago)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("period", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Aggregation period in seconds (minimum 60, default 300)"
                                )))
                                .putAdditionalProperty("stat", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("Average", "Maximum", "Minimum", "Sum"),
                                        "description", "Statistic to retrieve (default: Average)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("metricName")
                        .build())
                .build();
    }

    public static Tool awsRds() {
        return Tool.builder()
                .name("aws_rds")
                .description("Inspect AWS RDS DB instances and Aurora clusters, and fetch database metrics. "
                        + "Use this to list or describe RDS instances/clusters (engine, status, endpoint, storage, "
                        + "backup retention) or to retrieve CloudWatch AWS/RDS metrics such as CPUUtilization, "
                        + "DatabaseConnections, FreeStorageSpace, ReadLatency, or WriteLatency for a specific instance. "
                        + "Cross-account access is handled automatically via IAM role assumption.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("customerId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Customer ID from the registry (e.g. 'acme-corp')"
                                )))
                                .putAdditionalProperty("environmentName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Environment name (e.g. 'production', 'acceptance')"
                                )))
                                .putAdditionalProperty("action", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("list_instances", "describe_instance",
                                                "list_clusters", "describe_cluster", "get_instance_metrics"),
                                        "description", "Action to perform"
                                )))
                                .putAdditionalProperty("dbInstanceId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "DB instance identifier — required for describe_instance and get_instance_metrics"
                                )))
                                .putAdditionalProperty("dbClusterId", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "DB cluster identifier — required for describe_cluster"
                                )))
                                .putAdditionalProperty("metricName", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "CloudWatch metric name for get_instance_metrics, "
                                                + "e.g. CPUUtilization, DatabaseConnections, FreeStorageSpace, "
                                                + "ReadLatency, WriteLatency, ReadIOPS, WriteIOPS"
                                )))
                                .putAdditionalProperty("startTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Start of the time range, ISO-8601 (defaults to 1 hour ago)"
                                )))
                                .putAdditionalProperty("endTime", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "End of the time range, ISO-8601 (defaults to now)"
                                )))
                                .putAdditionalProperty("period", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Aggregation period in seconds for get_instance_metrics (minimum 60, default 300)"
                                )))
                                .putAdditionalProperty("stat", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("Average", "Maximum", "Minimum", "Sum"),
                                        "description", "Statistic to retrieve for get_instance_metrics (default: Average)"
                                )))
                                .build())
                        .addRequired("customerId")
                        .addRequired("environmentName")
                        .addRequired("action")
                        .build())
                .build();
    }
}
