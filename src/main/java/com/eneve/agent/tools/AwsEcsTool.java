package com.eneve.agent.tools;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListClustersRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only Claude tool for inspecting AWS ECS / Fargate resources.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code list_clusters} — list ECS cluster ARNs in the account</li>
 *   <li>{@code describe_cluster} — describe a specific cluster (active services, running tasks)</li>
 *   <li>{@code list_services} — list services in a cluster</li>
 *   <li>{@code describe_service} — describe a service (desired/running/pending counts, deployments)</li>
 *   <li>{@code list_tasks} — list task ARNs in a cluster/service (RUNNING or STOPPED)</li>
 *   <li>{@code describe_task} — describe tasks including container status and stop reason</li>
 *   <li>{@code describe_task_definition} — describe the task definition (image, env vars, CPU/memory)</li>
 * </ul>
 */
@ApplicationScoped
public class AwsEcsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AwsEcsTool.class);

    @Inject
    AwsClientFactory clientFactory;

    @Inject
    CustomerRegistryStore registryStore;

    @Override
    public String name() {
        return "aws_ecs";
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
            return "ERROR: 'action' is required (list_clusters | describe_cluster | list_services | "
                    + "describe_service | list_tasks | describe_task | describe_task_definition)";
        }

        try {
            AwsEnvConfig env = resolveEnv(customerId, environmentName);
            try (EcsClient client = clientFactory.ecsClient(env.roleArn(), env.region())) {
                return switch (action.toLowerCase()) {
                    case "list_clusters"         -> listClusters(client, env);
                    case "describe_cluster"      -> describeCluster(client, input, env);
                    case "list_services"         -> listServices(client, input, env);
                    case "describe_service"      -> describeService(client, input, env);
                    case "list_tasks"            -> listTasks(client, input, env);
                    case "describe_task"         -> describeTask(client, input, env);
                    case "describe_task_definition" -> describeTaskDefinition(client, input, env);
                    default -> "ERROR: Unknown action '" + action + "'. Valid: list_clusters, describe_cluster, "
                            + "list_services, describe_service, list_tasks, describe_task, describe_task_definition";
                };
            }
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            LOG.warnf("aws_ecs failed for customer=%s env=%s action=%s: %s",
                    customerId, environmentName, action, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────────

    private String listClusters(EcsClient client, AwsEnvConfig env) {
        List<String> arns = client.listClusters(ListClustersRequest.builder().maxResults(100).build()).clusterArns();
        StringBuilder sb = new StringBuilder();
        sb.append("ECS Clusters [").append(env.label()).append("]\n");
        sb.append("Found ").append(arns.size()).append(" cluster(s):\n\n");
        arns.forEach(arn -> sb.append("  ").append(arn).append("\n"));
        return sb.toString();
    }

    private String describeCluster(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String clusterArn = requireParam(input, "clusterArn");
        List<Cluster> clusters = client.describeClusters(
                DescribeClustersRequest.builder().clusters(clusterArn).build()
        ).clusters();

        if (clusters.isEmpty()) {
            return "No cluster found for ARN: " + clusterArn;
        }
        Cluster c = clusters.get(0);
        return "ECS Cluster [" + env.label() + "]\n"
                + "Name:             " + c.clusterName() + "\n"
                + "ARN:              " + c.clusterArn() + "\n"
                + "Status:           " + c.status() + "\n"
                + "Active services:  " + c.activeServicesCount() + "\n"
                + "Running tasks:    " + c.runningTasksCount() + "\n"
                + "Pending tasks:    " + c.pendingTasksCount() + "\n";
    }

    private String listServices(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String clusterArn = requireParam(input, "clusterArn");
        List<String> arns = client.listServices(
                ListServicesRequest.builder().cluster(clusterArn).maxResults(100).build()
        ).serviceArns();

        StringBuilder sb = new StringBuilder();
        sb.append("ECS Services in cluster [").append(env.label()).append("]\n");
        sb.append("Cluster: ").append(clusterArn).append("\n");
        sb.append("Found ").append(arns.size()).append(" service(s):\n\n");
        arns.forEach(arn -> sb.append("  ").append(arn).append("\n"));
        return sb.toString();
    }

    private String describeService(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String clusterArn = requireParam(input, "clusterArn");
        String serviceArn = requireParam(input, "serviceArn");

        List<Service> services = client.describeServices(
                DescribeServicesRequest.builder().cluster(clusterArn).services(serviceArn).build()
        ).services();

        if (services.isEmpty()) {
            return "No service found for ARN: " + serviceArn;
        }
        Service s = services.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("ECS Service [").append(env.label()).append("]\n");
        sb.append("Name:             ").append(s.serviceName()).append("\n");
        sb.append("Status:           ").append(s.status()).append("\n");
        sb.append("Task definition:  ").append(s.taskDefinition()).append("\n");
        sb.append("Desired count:    ").append(s.desiredCount()).append("\n");
        sb.append("Running count:    ").append(s.runningCount()).append("\n");
        sb.append("Pending count:    ").append(s.pendingCount()).append("\n");
        sb.append("Launch type:      ").append(s.launchTypeAsString()).append("\n");

        if (!s.deployments().isEmpty()) {
            sb.append("\nDeployments:\n");
            for (Deployment d : s.deployments()) {
                sb.append("  [").append(d.status()).append("] ")
                  .append(d.taskDefinition())
                  .append("  desired=").append(d.desiredCount())
                  .append(" running=").append(d.runningCount())
                  .append(" pending=").append(d.pendingCount());
                if (d.createdAt() != null) {
                    sb.append("  created=").append(d.createdAt());
                }
                if (d.rolloutStateAsString() != null) {
                    sb.append("  rollout=").append(d.rolloutStateAsString());
                }
                sb.append("\n");
            }
        }

        if (!s.events().isEmpty()) {
            sb.append("\nRecent events (last 5):\n");
            s.events().stream().limit(5).forEach(e ->
                sb.append("  ").append(e.createdAt()).append(" — ").append(e.message()).append("\n")
            );
        }
        return sb.toString();
    }

    private String listTasks(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String clusterArn = requireParam(input, "clusterArn");
        String desiredStatus = (String) input.get("desiredStatus");
        String serviceArn = (String) input.get("serviceArn");

        ListTasksRequest.Builder req = ListTasksRequest.builder()
                .cluster(clusterArn)
                .maxResults(100);

        if (desiredStatus != null && !desiredStatus.isBlank()) {
            req.desiredStatus(desiredStatus.toUpperCase());
        }
        if (serviceArn != null && !serviceArn.isBlank()) {
            req.serviceName(serviceArn);
        }

        List<String> arns = client.listTasks(req.build()).taskArns();
        StringBuilder sb = new StringBuilder();
        sb.append("ECS Tasks [").append(env.label()).append("]\n");
        sb.append("Cluster: ").append(clusterArn).append("\n");
        if (desiredStatus != null) sb.append("Status filter: ").append(desiredStatus.toUpperCase()).append("\n");
        sb.append("Found ").append(arns.size()).append(" task(s):\n\n");
        arns.forEach(arn -> sb.append("  ").append(arn).append("\n"));
        if (arns.size() == 100) {
            sb.append("\n... results may be truncated at 100 tasks\n");
        }
        return sb.toString();
    }

    private String describeTask(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String clusterArn = requireParam(input, "clusterArn");
        String taskArn = requireParam(input, "taskArn");

        List<Task> tasks = client.describeTasks(
                DescribeTasksRequest.builder().cluster(clusterArn).tasks(taskArn).build()
        ).tasks();

        if (tasks.isEmpty()) {
            return "No task found for ARN: " + taskArn;
        }
        Task t = tasks.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("ECS Task [").append(env.label()).append("]\n");
        sb.append("Task ARN:         ").append(t.taskArn()).append("\n");
        sb.append("Task definition:  ").append(t.taskDefinitionArn()).append("\n");
        sb.append("Last status:      ").append(t.lastStatus()).append("\n");
        sb.append("Desired status:   ").append(t.desiredStatus()).append("\n");
        sb.append("CPU:              ").append(t.cpu()).append("\n");
        sb.append("Memory:           ").append(t.memory()).append("\n");
        if (t.startedAt() != null) sb.append("Started at:       ").append(t.startedAt()).append("\n");
        if (t.stoppedAt() != null) sb.append("Stopped at:       ").append(t.stoppedAt()).append("\n");
        if (t.stoppedReason() != null && !t.stoppedReason().isBlank()) {
            sb.append("Stop reason:      ").append(t.stoppedReason()).append("\n");
        }

        if (!t.containers().isEmpty()) {
            sb.append("\nContainers:\n");
            for (Container c : t.containers()) {
                sb.append("  ").append(c.name())
                  .append("  status=").append(c.lastStatus());
                if (c.exitCode() != null) {
                    sb.append("  exitCode=").append(c.exitCode());
                }
                if (c.reason() != null && !c.reason().isBlank()) {
                    sb.append("  reason=").append(c.reason());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String describeTaskDefinition(EcsClient client, Map<String, Object> input, AwsEnvConfig env) {
        String taskDefArn = requireParam(input, "taskDefinitionArn");
        TaskDefinition td = client.describeTaskDefinition(
                DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
        ).taskDefinition();

        StringBuilder sb = new StringBuilder();
        sb.append("ECS Task Definition [").append(env.label()).append("]\n");
        sb.append("Family:           ").append(td.family()).append("\n");
        sb.append("Revision:         ").append(td.revision()).append("\n");
        sb.append("Status:           ").append(td.statusAsString()).append("\n");
        sb.append("CPU:              ").append(td.cpu()).append("\n");
        sb.append("Memory:           ").append(td.memory()).append("\n");
        sb.append("Network mode:     ").append(td.networkModeAsString()).append("\n");

        if (!td.containerDefinitions().isEmpty()) {
            sb.append("\nContainer definitions:\n");
            for (ContainerDefinition cd : td.containerDefinitions()) {
                sb.append("  [").append(cd.name()).append("]\n");
                sb.append("    Image:   ").append(cd.image()).append("\n");
                sb.append("    CPU:     ").append(cd.cpu()).append("\n");
                sb.append("    Memory:  ").append(cd.memory()).append("\n");
                if (!cd.environment().isEmpty()) {
                    sb.append("    Env vars (").append(cd.environment().size()).append(" defined — values redacted for security)\n");
                }
                if (!cd.portMappings().isEmpty()) {
                    sb.append("    Ports:   ");
                    cd.portMappings().forEach(p ->
                        sb.append(p.containerPort()).append("/").append(p.protocolAsString()).append(" ")
                    );
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

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
