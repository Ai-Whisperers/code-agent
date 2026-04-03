package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.architecture.ArchitectureDiagramDto;
import com.eneve.agent.architecture.ArchitectureDiagramStore;
import com.eneve.agent.architecture.StructurizrService;
import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.*;
import com.eneve.agent.tools.AwsClientFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInternetGatewaysRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InternetGateway;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListClustersRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersRequest;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheSubnetGroupsRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionsRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles {@link JobType#GENERATE_CLOUD_ARCHITECTURE} jobs.
 *
 * <p>Performs a broad read-only discovery pass across the customer's AWS account:
 * VPCs, subnets, EC2 instances, ECS clusters/services, RDS instances/clusters,
 * ALB/NLB load balancers, ElastiCache clusters, Lambda functions, and S3 buckets.
 *
 * <p>Produces three Structurizr DSL views:
 * <ul>
 *   <li><b>SystemContext</b> — the customer system and its external actors/dependencies.</li>
 *   <li><b>Containers</b>   — all discovered compute/data containers.</li>
 *   <li><b>Deployment</b>   — VPC/subnet topology with containers placed in their networks.</li>
 * </ul>
 *
 * <p>Human-added elements tagged {@code !human} in the pinned DSL are preserved verbatim.
 */
@ApplicationScoped
public class GenerateCloudArchitectureHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateCloudArchitectureHandler.class);

    @Inject AwsClientFactory clientFactory;
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

        String roleArn  = env.aws().iamRole()  != null ? env.aws().iamRole()  : "";
        String region   = env.aws().region()   != null ? env.aws().region()   : "";
        CloudAccount cloudAccount = customer.cloudAccountId() != null && !customer.cloudAccountId().isBlank()
                ? cloudAccountStore.getCloudAccountUnmasked(customer.cloudAccountId()).orElse(null)
                : null;

        DiscoveryResult discovery = new DiscoveryResult();

        // ── VPC / networking + ENIs ───────────────────────────────────────────
        try (Ec2Client ec2 = clientFactory.ec2Client(roleArn, region, cloudAccount)) {
            discovery.vpcs = ec2.describeVpcs(DescribeVpcsRequest.builder().build()).vpcs();
            discovery.subnets = ec2.describeSubnets(DescribeSubnetsRequest.builder().build()).subnets();
            discovery.instances = ec2.describeInstances(DescribeInstancesRequest.builder().build())
                    .reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .filter(i -> !"terminated".equals(i.state().nameAsString()))
                    .collect(Collectors.toList());
            // EC2 instance → subnet from primary network interface
            for (Instance inst : discovery.instances) {
                String name = tagValue(inst.tags(), "Name");
                String key  = name.isBlank() ? inst.instanceId() : name;
                if (inst.subnetId() != null && !inst.subnetId().isBlank()) {
                    discovery.resourceSubnetMap.put(key, inst.subnetId());
                }
            }
            discovery.securityGroups = ec2.describeSecurityGroups(
                    DescribeSecurityGroupsRequest.builder().build()).securityGroups();
            try {
                discovery.natGateways = ec2.describeNatGateways(
                        DescribeNatGatewaysRequest.builder().build()).natGateways();
            } catch (Exception e) {
                LOG.debugf("NAT gateway discovery skipped: %s", e.getMessage());
            }
            try {
                discovery.internetGateways = ec2.describeInternetGateways(
                        DescribeInternetGatewaysRequest.builder().build()).internetGateways();
            } catch (Exception e) {
                LOG.debugf("Internet gateway discovery skipped: %s", e.getMessage());
            }
            // ENI map: eni-id → subnet-id (used to resolve ECS task placement)
            try {
                List<NetworkInterface> enis = ec2.describeNetworkInterfaces(
                        DescribeNetworkInterfacesRequest.builder().build()).networkInterfaces();
                for (NetworkInterface eni : enis) {
                    if (eni.networkInterfaceId() != null && eni.subnetId() != null) {
                        discovery.eniSubnetMap.put(eni.networkInterfaceId(), eni.subnetId());
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ENI discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("EC2/VPC discovery failed: %s", e.getMessage());
        }

        // ── ECS (services + task ENI placement) ───────────────────────────────
        try (EcsClient ecs = clientFactory.ecsClient(roleArn, region, cloudAccount)) {
            List<String> clusterArns = ecs.listClusters(
                    ListClustersRequest.builder().maxResults(100).build()).clusterArns();
            for (String clusterArn : clusterArns) {
                String clusterName = arnToName(clusterArn);
                List<EcsServiceInfo> services = new ArrayList<>();
                List<String> serviceArns = ecs.listServices(
                        ListServicesRequest.builder().cluster(clusterArn).maxResults(100).build()
                ).serviceArns();
                if (!serviceArns.isEmpty()) {
                    for (Service svc : ecs.describeServices(DescribeServicesRequest.builder()
                            .cluster(clusterArn).services(serviceArns).build()).services()) {
                        String image = resolveImage(ecs, svc.taskDefinition());
                        services.add(new EcsServiceInfo(svc.serviceName(), image,
                                svc.launchTypeAsString(), svc.desiredCount()));
                    }
                }
                // Resolve subnet for each service via a running task's ENI
                try {
                    List<String> taskArns = ecs.listTasks(
                            ListTasksRequest.builder().cluster(clusterArn).maxResults(100).build()
                    ).taskArns();
                    if (!taskArns.isEmpty()) {
                        List<Task> tasks = ecs.describeTasks(
                                DescribeTasksRequest.builder().cluster(clusterArn).tasks(taskArns).build()
                        ).tasks();
                        // Build serviceName → subnetId from task attachments
                        Map<String, String> svcSubnet = new HashMap<>();
                        for (Task task : tasks) {
                            String svcName = task.group() != null
                                    ? task.group().replaceFirst("^service:", "") : null;
                            if (svcName == null) continue;
                            for (var attachment : task.attachments()) {
                                if (!"ElasticNetworkInterface".equals(attachment.type())) continue;
                                for (var detail : attachment.details()) {
                                    if ("subnetId".equals(detail.name())) {
                                        svcSubnet.put(svcName, detail.value());
                                    } else if ("networkInterfaceId".equals(detail.name())) {
                                        String subnet = discovery.eniSubnetMap.get(detail.value());
                                        if (subnet != null) svcSubnet.putIfAbsent(svcName, subnet);
                                    }
                                }
                            }
                        }
                        for (EcsServiceInfo svc : services) {
                            String subnet = svcSubnet.get(svc.name());
                            if (subnet != null) discovery.resourceSubnetMap.put(svc.name(), subnet);
                        }
                    }
                } catch (Exception e) {
                    LOG.debugf("ECS task ENI resolution skipped for cluster %s: %s", clusterName, e.getMessage());
                }
                discovery.ecsClusters.add(new EcsClusterInfo(clusterName, clusterArn, services));
            }
        } catch (Exception e) {
            LOG.warnf("ECS discovery failed: %s", e.getMessage());
        }

        // ── RDS ───────────────────────────────────────────────────────────────
        try (RdsClient rds = clientFactory.rdsClient(roleArn, region, cloudAccount)) {
            rds.describeDBInstances(DescribeDbInstancesRequest.builder().build()).dbInstances()
                    .forEach(db -> {
                        String firstSubnet = null;
                        if (db.dbSubnetGroup() != null && db.dbSubnetGroup().subnets() != null
                                && !db.dbSubnetGroup().subnets().isEmpty()) {
                            firstSubnet = db.dbSubnetGroup().subnets().get(0).subnetIdentifier();
                        }
                        discovery.rdsInstances.add(new RdsInstanceInfo(
                                db.dbInstanceIdentifier(), db.engine(),
                                db.dbInstanceClass(), db.multiAZ(),
                                db.dbSubnetGroup() != null ? db.dbSubnetGroup().vpcId() : null));
                        if (firstSubnet != null) {
                            discovery.resourceSubnetMap.put(db.dbInstanceIdentifier(), firstSubnet);
                        }
                    });
            try {
                rds.describeDBClusters(DescribeDbClustersRequest.builder().build()).dbClusters()
                        .forEach(c -> {
                            discovery.rdsClusters.add(c);
                            if (c.dbSubnetGroup() != null && !c.dbSubnetGroup().isBlank()) {
                                // subnet group name only — resolved below if needed
                                discovery.resourceSubnetMap.putIfAbsent(c.dbClusterIdentifier(), c.dbSubnetGroup());
                            }
                        });
            } catch (Exception e) {
                LOG.debugf("RDS cluster discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("RDS discovery failed: %s", e.getMessage());
        }

        // ── ALB / NLB ─────────────────────────────────────────────────────────
        try (ElasticLoadBalancingV2Client elb = clientFactory.elbV2Client(roleArn, region, cloudAccount)) {
            discovery.loadBalancers = elb.describeLoadBalancers(
                    DescribeLoadBalancersRequest.builder().build()).loadBalancers();
        } catch (Exception e) {
            LOG.warnf("ELB discovery failed: %s", e.getMessage());
        }

        // ── ElastiCache ───────────────────────────────────────────────────────
        try (ElastiCacheClient ec = clientFactory.elastiCacheClient(roleArn, region, cloudAccount)) {
            discovery.cacheClusters = ec.describeCacheClusters(
                    DescribeCacheClustersRequest.builder().showCacheNodeInfo(true).build()
            ).cacheClusters();
            // Resolve subnet for each cluster via its subnet group
            try {
                var subnetGroups = ec.describeCacheSubnetGroups(
                        DescribeCacheSubnetGroupsRequest.builder().build()).cacheSubnetGroups();
                Map<String, String> groupToSubnet = new HashMap<>();
                for (var sg : subnetGroups) {
                    if (sg.subnets() != null && !sg.subnets().isEmpty()) {
                        groupToSubnet.put(sg.cacheSubnetGroupName(),
                                sg.subnets().get(0).subnetIdentifier());
                    }
                }
                for (CacheCluster cc : discovery.cacheClusters) {
                    if (cc.cacheSubnetGroupName() != null) {
                        String subnet = groupToSubnet.get(cc.cacheSubnetGroupName());
                        if (subnet != null) discovery.resourceSubnetMap.put(cc.cacheClusterId(), subnet);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ElastiCache subnet group resolution skipped: %s", e.getMessage());
            }
            try {
                discovery.replicationGroups = ec.describeReplicationGroups(
                        DescribeReplicationGroupsRequest.builder().build()).replicationGroups();
                // Replication group subnet: derive from first member cluster
                for (ReplicationGroup rg : discovery.replicationGroups) {
                    if (rg.memberClusters() != null && !rg.memberClusters().isEmpty()) {
                        String firstMember = rg.memberClusters().get(0);
                        String subnet = discovery.resourceSubnetMap.get(firstMember);
                        if (subnet != null) discovery.resourceSubnetMap.putIfAbsent(rg.replicationGroupId(), subnet);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ElastiCache replication group discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("ElastiCache discovery failed: %s", e.getMessage());
        }

        // ── Lambda ────────────────────────────────────────────────────────────
        try (LambdaClient lambda = clientFactory.lambdaClient(roleArn, region, cloudAccount)) {
            String marker = null;
            do {
                var req = ListFunctionsRequest.builder().maxItems(50);
                if (marker != null) req.marker(marker);
                var resp = lambda.listFunctions(req.build());
                discovery.lambdaFunctions.addAll(resp.functions());
                marker = resp.nextMarker();
            } while (marker != null);
            // Resolve subnet from VPC config (first subnet in the list)
            for (FunctionConfiguration fn : discovery.lambdaFunctions) {
                if (fn.vpcConfig() != null && fn.vpcConfig().subnetIds() != null
                        && !fn.vpcConfig().subnetIds().isEmpty()) {
                    discovery.resourceSubnetMap.put(fn.functionName(),
                            fn.vpcConfig().subnetIds().get(0));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Lambda discovery failed: %s", e.getMessage());
        }

        // ── S3 ────────────────────────────────────────────────────────────────
        try (S3Client s3 = clientFactory.s3Client(roleArn, region, cloudAccount)) {
            discovery.s3Buckets = s3.listBuckets().buckets();
        } catch (Exception e) {
            LOG.warnf("S3 discovery failed: %s", e.getMessage());
        }

        // ── CloudFront ────────────────────────────────────────────────────────
        try (CloudFrontClient cf = clientFactory.cloudFrontClient(roleArn, cloudAccount)) {
            String marker = null;
            do {
                var req = ListDistributionsRequest.builder();
                if (marker != null) req.marker(marker);
                var resp = cf.listDistributions(req.build());
                if (resp.distributionList() != null) {
                    discovery.cfDistributions.addAll(resp.distributionList().items());
                    marker = resp.distributionList().isTruncated()
                            ? resp.distributionList().nextMarker() : null;
                } else {
                    marker = null;
                }
            } while (marker != null);
        } catch (Exception e) {
            LOG.warnf("CloudFront discovery failed: %s", e.getMessage());
        }

        if (discovery.isEmpty()) {
            failJob(job, "No AWS resources discovered — check credentials and IAM permissions");
            return;
        }

        Optional<String> pinnedDsl = diagramStore.findPinnedCloudDsl(
                request.customerId(), request.environmentName());

        String dslContent = buildDsl(customer.name(), request.environmentName(), region, discovery,
                pinnedDsl.orElse(""));

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

    // ── DSL builder ───────────────────────────────────────────────────────────

    private String buildDsl(String customerName, String environmentName, String region,
                            DiscoveryResult d, String pinnedDsl) {

        String humanElements = extractHumanElements(pinnedDsl);
        String systemId = toId(customerName);
        String envLabel = escape(environmentName);

        StringBuilder sb = new StringBuilder();
        sb.append("workspace \"").append(escape(customerName)).append(" — ")
          .append(envLabel).append("\" {\n\n");
        sb.append("    !identifiers hierarchical\n\n");
        sb.append("    model {\n\n");

        // ── External actors ───────────────────────────────────────────────────
        sb.append("        user = person \"End User\" \"External user of the system\"\n");
        sb.append("        internet = softwareSystem \"Internet\" \"Public internet\" {\n");
        sb.append("            tags \"External\"\n");
        sb.append("        }\n\n");

        // ── Main system ───────────────────────────────────────────────────────
        sb.append("        ").append(systemId).append(" = softwareSystem \"")
          .append(escape(customerName)).append("\" \"AWS-hosted system in ")
          .append(envLabel).append(" (").append(region).append(")\" {\n\n");

        // CloudFront distributions
        if (!d.cfDistributions.isEmpty()) {
            sb.append("            // CloudFront Distributions\n");
        }
        for (DistributionSummary dist : d.cfDistributions) {
            String cfId = toId(dist.id());
            String comment = dist.comment() != null && !dist.comment().isBlank()
                    ? dist.comment() : dist.domainName();
            sb.append("            ").append(cfId).append(" = container \"")
              .append(escape(comment)).append("\" \"CloudFront CDN (")
              .append(escape(dist.domainName())).append(")\" \"AWS CloudFront\" {\n");
            sb.append("                tags \"CDN\"\n");
            sb.append("            }\n");
        }

        // Load balancers
        for (LoadBalancer lb : d.loadBalancers) {
            String lbId = toId(lb.loadBalancerName());
            String lbType = lb.typeAsString() != null ? lb.typeAsString().toUpperCase() : "ALB";
            sb.append("            ").append(lbId).append(" = container \"")
              .append(escape(lb.loadBalancerName())).append("\" \"")
              .append(lbType).append(" load balancer\" \"AWS ").append(lbType).append("\" {\n");
            sb.append("                tags \"LoadBalancer\"\n");
            sb.append("            }\n");
        }

        // ECS services
        for (EcsClusterInfo cluster : d.ecsClusters) {
            sb.append("\n            // ECS Cluster: ").append(cluster.name()).append("\n");
            for (EcsServiceInfo svc : cluster.services()) {
                String svcId = toId(svc.name());
                String tech  = svc.launchType() != null ? svc.launchType() : "ECS/Fargate";
                String desc  = svc.image() != null && !svc.image().isBlank()
                        ? truncate(svc.image(), 80) : "ECS service in " + cluster.name();
                sb.append("            ").append(svcId).append(" = container \"")
                  .append(escape(svc.name())).append("\" \"").append(escape(desc))
                  .append("\" \"").append(tech).append("\" {\n");
                sb.append("                tags \"ECS\"\n");
                sb.append("            }\n");
            }
        }

        // EC2 instances (group by Name tag, skip ECS-managed)
        Map<String, List<Instance>> ec2ByName = d.instances.stream()
                .collect(Collectors.groupingBy(i -> {
                    String name = tagValue(i.tags(), "Name");
                    return name.isBlank() ? i.instanceId() : name;
                }));
        if (!ec2ByName.isEmpty()) {
            sb.append("\n            // EC2 Instances\n");
        }
        for (Map.Entry<String, List<Instance>> entry : ec2ByName.entrySet()) {
            Instance first = entry.getValue().get(0);
            String ec2Id = toId(entry.getKey());
            String iType = first.instanceTypeAsString() != null ? first.instanceTypeAsString() : "EC2";
            String desc  = entry.getValue().size() > 1
                    ? entry.getValue().size() + "x " + iType
                    : iType + " instance";
            sb.append("            ").append(ec2Id).append(" = container \"")
              .append(escape(entry.getKey())).append("\" \"").append(escape(desc))
              .append("\" \"EC2\" {\n");
            sb.append("                tags \"EC2\"\n");
            sb.append("            }\n");
        }

        // RDS instances
        if (!d.rdsInstances.isEmpty()) {
            sb.append("\n            // RDS Databases\n");
        }
        for (RdsInstanceInfo rds : d.rdsInstances) {
            String rdsId = toId(rds.id());
            String tech  = rds.engine() != null ? rds.engine() : "RDS";
            String desc  = (rds.instanceClass() != null ? rds.instanceClass() : "RDS")
                    + (rds.multiAz() ? " (Multi-AZ)" : "");
            sb.append("            ").append(rdsId).append(" = container \"")
              .append(escape(rds.id())).append("\" \"").append(escape(desc))
              .append("\" \"").append(tech).append("\" {\n");
            sb.append("                tags \"Database\"\n");
            sb.append("            }\n");
        }

        // RDS Aurora clusters (skip instances already covered above)
        Set<String> rdsInstanceIds = d.rdsInstances.stream()
                .map(r -> r.id()).collect(Collectors.toSet());
        for (DBCluster cluster : d.rdsClusters) {
            String cId = toId(cluster.dbClusterIdentifier());
            if (rdsInstanceIds.contains(cluster.dbClusterIdentifier())) continue;
            String tech = cluster.engine() != null ? cluster.engine() : "Aurora";
            sb.append("            ").append(cId).append(" = container \"")
              .append(escape(cluster.dbClusterIdentifier())).append("\" \"Aurora cluster\" \"")
              .append(tech).append("\" {\n");
            sb.append("                tags \"Database\"\n");
            sb.append("            }\n");
        }

        // ElastiCache
        Set<String> seenCacheGroups = new HashSet<>();
        for (ReplicationGroup rg : d.replicationGroups) {
            seenCacheGroups.add(rg.replicationGroupId());
            String rgId = toId(rg.replicationGroupId());
            String desc = rg.description() != null && !rg.description().isBlank()
                    ? rg.description() : "Redis replication group";
            sb.append("            ").append(rgId).append(" = container \"")
              .append(escape(rg.replicationGroupId())).append("\" \"").append(escape(desc))
              .append("\" \"Redis\" {\n");
            sb.append("                tags \"Cache\"\n");
            sb.append("            }\n");
        }
        for (CacheCluster cc : d.cacheClusters) {
            if (cc.replicationGroupId() != null && seenCacheGroups.contains(cc.replicationGroupId())) continue;
            String ccId = toId(cc.cacheClusterId());
            String tech = cc.engine() != null ? cc.engine() : "ElastiCache";
            sb.append("            ").append(ccId).append(" = container \"")
              .append(escape(cc.cacheClusterId())).append("\" \"ElastiCache cluster\" \"")
              .append(tech).append("\" {\n");
            sb.append("                tags \"Cache\"\n");
            sb.append("            }\n");
        }

        // Lambda functions (group by prefix to avoid noise)
        if (!d.lambdaFunctions.isEmpty()) {
            sb.append("\n            // Lambda Functions\n");
            for (FunctionConfiguration fn : d.lambdaFunctions) {
                String fnId = toId(fn.functionName());
                String runtime = fn.runtimeAsString() != null ? fn.runtimeAsString() : "Lambda";
                String desc = fn.description() != null && !fn.description().isBlank()
                        ? fn.description() : "Lambda function";
                sb.append("            ").append(fnId).append(" = container \"")
                  .append(escape(fn.functionName())).append("\" \"").append(escape(truncate(desc, 80)))
                  .append("\" \"").append(runtime).append("\" {\n");
                sb.append("                tags \"Lambda\"\n");
                sb.append("            }\n");
            }
        }

        // S3 buckets (as external storage containers)
        if (!d.s3Buckets.isEmpty()) {
            sb.append("\n            // S3 Buckets\n");
            for (Bucket bucket : d.s3Buckets) {
                String bId = toId(bucket.name());
                sb.append("            ").append(bId).append(" = container \"")
                  .append(escape(bucket.name())).append("\" \"S3 object storage\" \"S3\" {\n");
                sb.append("                tags \"Storage\"\n");
                sb.append("            }\n");
            }
        }

        // Human-preserved elements
        if (!humanElements.isBlank()) {
            sb.append("\n            // Human-added elements (preserved from pinned version)\n");
            sb.append(humanElements).append("\n");
        }

        sb.append("        }\n\n"); // end softwareSystem

        // ── Relationships ─────────────────────────────────────────────────────
        // User → CloudFront → LB (or directly to system if neither present)
        if (!d.cfDistributions.isEmpty()) {
            for (DistributionSummary dist : d.cfDistributions) {
                sb.append("        user -> ").append(systemId).append(".")
                  .append(toId(dist.id())).append(" \"Requests via\" \"HTTPS\"\n");
            }
            // CF → LBs
            for (DistributionSummary dist : d.cfDistributions) {
                for (LoadBalancer lb : d.loadBalancers) {
                    sb.append("        ").append(systemId).append(".").append(toId(dist.id()))
                      .append(" -> ").append(systemId).append(".").append(toId(lb.loadBalancerName()))
                      .append(" \"Forwards to\" \"HTTPS\"\n");
                }
            }
        } else if (!d.loadBalancers.isEmpty()) {
            for (LoadBalancer lb : d.loadBalancers) {
                sb.append("        user -> ").append(systemId).append(".")
                  .append(toId(lb.loadBalancerName())).append(" \"Uses\" \"HTTPS\"\n");
            }
        } else {
            sb.append("        user -> ").append(systemId).append(" \"Uses\" \"HTTPS\"\n");
        }
        // LB → ECS services (first cluster)
        if (!d.loadBalancers.isEmpty() && !d.ecsClusters.isEmpty()) {
            EcsClusterInfo firstCluster = d.ecsClusters.get(0);
            for (LoadBalancer lb : d.loadBalancers) {
                for (EcsServiceInfo svc : firstCluster.services()) {
                    sb.append("        ").append(systemId).append(".").append(toId(lb.loadBalancerName()))
                      .append(" -> ").append(systemId).append(".").append(toId(svc.name()))
                      .append(" \"Routes to\" \"HTTP\"\n");
                }
            }
        }
        // ECS → RDS
        for (EcsClusterInfo cluster : d.ecsClusters) {
            for (EcsServiceInfo svc : cluster.services()) {
                for (RdsInstanceInfo rds : d.rdsInstances) {
                    sb.append("        ").append(systemId).append(".").append(toId(svc.name()))
                      .append(" -> ").append(systemId).append(".").append(toId(rds.id()))
                      .append(" \"Reads/writes\" \"JDBC\"\n");
                }
            }
        }
        // ECS → ElastiCache
        for (EcsClusterInfo cluster : d.ecsClusters) {
            for (EcsServiceInfo svc : cluster.services()) {
                for (ReplicationGroup rg : d.replicationGroups) {
                    sb.append("        ").append(systemId).append(".").append(toId(svc.name()))
                      .append(" -> ").append(systemId).append(".").append(toId(rg.replicationGroupId()))
                      .append(" \"Caches via\" \"Redis\"\n");
                }
            }
        }

        // ── Deployment environment — grouped by actual subnet ─────────────────
        if (!d.vpcs.isEmpty()) {
            // Build subnetId → subnet name/cidr for labels
            Map<String, Subnet> subnetById = d.subnets.stream()
                    .collect(Collectors.toMap(Subnet::subnetId, s -> s, (a, b) -> a));

            sb.append("\n        deploymentEnvironment \"").append(envLabel).append("\" {\n");

            // CloudFront lives outside any VPC — environment level
            for (DistributionSummary dist : d.cfDistributions) {
                sb.append("            deploymentNode \"CloudFront\" \"AWS Global CDN\" \"AWS CloudFront\" {\n");
                sb.append("                containerInstance ").append(systemId).append(".")
                  .append(toId(dist.id())).append("\n");
                sb.append("            }\n");
            }

            // S3 is region-scoped — environment level
            if (!d.s3Buckets.isEmpty()) {
                sb.append("            deploymentNode \"S3\" \"Object storage\" \"AWS S3\" {\n");
                for (Bucket bucket : d.s3Buckets) {
                    sb.append("                containerInstance ").append(systemId).append(".")
                      .append(toId(bucket.name())).append("\n");
                }
                sb.append("            }\n");
            }

            // Lambda functions not in a VPC — environment level
            List<FunctionConfiguration> nonVpcLambdas = d.lambdaFunctions.stream()
                    .filter(fn -> fn.vpcConfig() == null || fn.vpcConfig().subnetIds().isEmpty())
                    .collect(Collectors.toList());
            if (!nonVpcLambdas.isEmpty()) {
                sb.append("            deploymentNode \"Lambda\" \"Serverless (no VPC)\" \"AWS Lambda\" {\n");
                for (FunctionConfiguration fn : nonVpcLambdas) {
                    sb.append("                containerInstance ").append(systemId).append(".")
                      .append(toId(fn.functionName())).append("\n");
                }
                sb.append("            }\n");
            }

            for (Vpc vpc : d.vpcs) {
                String vpcName = tagValue(vpc.tags(), "Name");
                if (vpcName.isBlank()) vpcName = vpc.vpcId();
                sb.append("            deploymentNode \"").append(escape(vpcName))
                  .append("\" \"VPC ").append(vpc.vpcId())
                  .append(" (").append(vpc.cidrBlock()).append(")\" \"AWS VPC\" {\n");

                boolean hasIgw = d.internetGateways.stream()
                        .anyMatch(igw -> igw.attachments().stream()
                                .anyMatch(a -> vpc.vpcId().equals(a.vpcId())));
                if (hasIgw) {
                    sb.append("                deploymentNode \"Internet Gateway\" \"IGW\" \"AWS IGW\" {\n");
                    sb.append("                }\n");
                }
                long natCount = d.natGateways.stream()
                        .filter(ng -> vpc.vpcId().equals(ng.vpcId())).count();
                if (natCount > 0) {
                    sb.append("                deploymentNode \"NAT Gateway\" \"")
                      .append(natCount).append(" NAT gateway(s)\" \"AWS NAT\" {\n");
                    sb.append("                }\n");
                }

                // ── Group resources by subnet ──────────────────────────────
                // Build a map: subnetId → list of "containerInstance <id>" lines
                Map<String, List<String>> subnetInstances = new LinkedHashMap<>();
                // Unplaced resources (no subnet info) go into a fallback bucket
                List<String> unplacedInstances = new ArrayList<>();

                // Helper to add a resource to the right bucket
                java.util.function.BiConsumer<String, String> place = (resourceKey, containerId) -> {
                    String subnetId = d.resourceSubnetMap.get(resourceKey);
                    if (subnetId != null && subnetById.containsKey(subnetId)) {
                        subnetInstances.computeIfAbsent(subnetId, k -> new ArrayList<>()).add(containerId);
                    } else {
                        unplacedInstances.add(containerId);
                    }
                };

                // Load balancers
                for (LoadBalancer lb : d.loadBalancers) {
                    // LBs span subnets; use first available subnet in this VPC as placement
                    String lbSubnet = lb.availabilityZones().stream()
                            .map(az -> az.subnetId())
                            .filter(sid -> sid != null && subnetById.containsKey(sid)
                                    && vpc.vpcId().equals(subnetById.get(sid).vpcId()))
                            .findFirst().orElse(null);
                    String cid = systemId + "." + toId(lb.loadBalancerName());
                    if (lbSubnet != null) {
                        subnetInstances.computeIfAbsent(lbSubnet, k -> new ArrayList<>()).add(cid);
                    } else {
                        unplacedInstances.add(cid);
                    }
                }

                // ECS services
                for (EcsClusterInfo cluster : d.ecsClusters) {
                    for (EcsServiceInfo svc : cluster.services()) {
                        place.accept(svc.name(), systemId + "." + toId(svc.name()));
                    }
                }

                // EC2 instances
                for (Instance inst : d.instances) {
                    if (!vpc.vpcId().equals(inst.vpcId())) continue;
                    String name = tagValue(inst.tags(), "Name");
                    String key  = name.isBlank() ? inst.instanceId() : name;
                    place.accept(key, systemId + "." + toId(key));
                }

                // RDS instances
                for (RdsInstanceInfo rds : d.rdsInstances) {
                    if (!vpc.vpcId().equals(rds.vpcId())) continue;
                    place.accept(rds.id(), systemId + "." + toId(rds.id()));
                }
                // RDS clusters (skip if already covered by instance)
                Set<String> deployRdsInstanceIds = d.rdsInstances.stream()
                        .map(RdsInstanceInfo::id).collect(Collectors.toSet());
                for (DBCluster cluster : d.rdsClusters) {
                    if (deployRdsInstanceIds.contains(cluster.dbClusterIdentifier())) continue;
                    place.accept(cluster.dbClusterIdentifier(),
                            systemId + "." + toId(cluster.dbClusterIdentifier()));
                }

                // ElastiCache
                Set<String> seenGroups = new HashSet<>();
                for (ReplicationGroup rg : d.replicationGroups) {
                    seenGroups.add(rg.replicationGroupId());
                    place.accept(rg.replicationGroupId(),
                            systemId + "." + toId(rg.replicationGroupId()));
                }
                for (CacheCluster cc : d.cacheClusters) {
                    if (cc.replicationGroupId() != null && seenGroups.contains(cc.replicationGroupId())) continue;
                    place.accept(cc.cacheClusterId(), systemId + "." + toId(cc.cacheClusterId()));
                }

                // VPC-attached Lambda functions
                for (FunctionConfiguration fn : d.lambdaFunctions) {
                    if (fn.vpcConfig() == null || fn.vpcConfig().subnetIds().isEmpty()) continue;
                    // Check if any of its subnets belong to this VPC
                    boolean inThisVpc = fn.vpcConfig().subnetIds().stream()
                            .anyMatch(sid -> subnetById.containsKey(sid)
                                    && vpc.vpcId().equals(subnetById.get(sid).vpcId()));
                    if (!inThisVpc) continue;
                    place.accept(fn.functionName(), systemId + "." + toId(fn.functionName()));
                }

                // ── Emit one deploymentNode per subnet that has resources ──
                for (Map.Entry<String, List<String>> entry : subnetInstances.entrySet()) {
                    Subnet subnet = subnetById.get(entry.getKey());
                    String subnetName = tagValue(subnet.tags(), "Name");
                    if (subnetName.isBlank()) subnetName = subnet.subnetId();
                    String subnetLabel = subnet.cidrBlock() != null
                            ? subnetName + " (" + subnet.cidrBlock() + ")" : subnetName;
                    String subnetType  = Boolean.TRUE.equals(subnet.mapPublicIpOnLaunch())
                            ? "Public Subnet" : "Private Subnet";
                    sb.append("                deploymentNode \"").append(escape(subnetLabel))
                      .append("\" \"").append(subnetType).append("\" \"AWS Subnet\" {\n");
                    for (String cid : entry.getValue()) {
                        sb.append("                    containerInstance ").append(cid).append("\n");
                    }
                    sb.append("                }\n");
                }

                // Unplaced resources go into a generic tier inside the VPC
                if (!unplacedInstances.isEmpty()) {
                    sb.append("                deploymentNode \"Other\" \"Resources without subnet info\" \"AWS\" {\n");
                    for (String cid : unplacedInstances) {
                        sb.append("                    containerInstance ").append(cid).append("\n");
                    }
                    sb.append("                }\n");
                }

                sb.append("            }\n"); // end VPC deploymentNode
            }

            sb.append("        }\n"); // end deploymentEnvironment
        }

        sb.append("\n    }\n\n"); // end model

        // ── Views ─────────────────────────────────────────────────────────────
        sb.append("    views {\n\n");

        // System Context
        sb.append("        systemContext ").append(systemId).append(" \"SystemContext\" {\n");
        sb.append("            include *\n");
        sb.append("            title \"").append(escape(customerName)).append(" — System Context (")
          .append(envLabel).append(")\"\n");
        sb.append("        }\n\n");

        // Container view
        sb.append("        container ").append(systemId).append(" \"Containers\" {\n");
        sb.append("            include *\n");
        sb.append("            title \"").append(escape(customerName)).append(" — Containers (")
          .append(envLabel).append(")\"\n");
        sb.append("        }\n\n");

        // Deployment view — references the environment declared in the model
        if (!d.vpcs.isEmpty()) {
            sb.append("        deployment * \"").append(envLabel).append("\" \"Deployment\" {\n");
            sb.append("            include *\n");
            sb.append("            title \"").append(escape(customerName))
              .append(" — Deployment (").append(envLabel).append(")\"\n");
            sb.append("        }\n\n");
        }

        // ── Styles ────────────────────────────────────────────────────────────
        sb.append("        styles {\n");
        sb.append("            element \"Person\" {\n");
        sb.append("                shape Person\n");
        sb.append("            }\n");
        sb.append("            element \"Database\" {\n");
        sb.append("                shape Cylinder\n");
        sb.append("            }\n");
        sb.append("            element \"Cache\" {\n");
        sb.append("                shape Cylinder\n");
        sb.append("                background #FF6B6B\n");
        sb.append("                color #ffffff\n");
        sb.append("            }\n");
        sb.append("            element \"ECS\" {\n");
        sb.append("                background #2196F3\n");
        sb.append("                color #ffffff\n");
        sb.append("            }\n");
        sb.append("            element \"EC2\" {\n");
        sb.append("                background #FF9800\n");
        sb.append("                color #ffffff\n");
        sb.append("            }\n");
        sb.append("            element \"Lambda\" {\n");
        sb.append("                background #9C27B0\n");
        sb.append("                color #ffffff\n");
        sb.append("                shape Component\n");
        sb.append("            }\n");
        sb.append("            element \"CDN\" {\n");
        sb.append("                background #00BCD4\n");
        sb.append("                color #ffffff\n");
        sb.append("                shape Hexagon\n");
        sb.append("            }\n");
        sb.append("            element \"LoadBalancer\" {\n");
        sb.append("                background #4CAF50\n");
        sb.append("                color #ffffff\n");
        sb.append("                shape Hexagon\n");
        sb.append("            }\n");
        sb.append("            element \"Storage\" {\n");
        sb.append("                shape Cylinder\n");
        sb.append("                background #795548\n");
        sb.append("                color #ffffff\n");
        sb.append("            }\n");
        sb.append("            element \"External\" {\n");
        sb.append("                background #999999\n");
        sb.append("                color #ffffff\n");
        sb.append("            }\n");
        sb.append("        }\n\n");

        sb.append("    }\n"); // end views
        sb.append("}\n");

        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractHumanElements(String pinnedDsl) {
        if (pinnedDsl == null || pinnedDsl.isBlank()) return "";
        return pinnedDsl.lines()
                .filter(l -> l.contains("!human"))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private String resolveImage(EcsClient ecsClient, String taskDefArn) {
        if (taskDefArn == null || taskDefArn.isBlank()) return "";
        try {
            TaskDefinition td = ecsClient.describeTaskDefinition(
                    DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
            ).taskDefinition();
            if (td.containerDefinitions() != null && !td.containerDefinitions().isEmpty()) {
                return td.containerDefinitions().get(0).image();
            }
        } catch (Exception e) {
            LOG.debugf("Could not resolve task definition image for %s: %s", taskDefArn, e.getMessage());
        }
        return "";
    }

    private static String tagValue(List<Tag> tags, String key) {
        if (tags == null) return "";
        return tags.stream().filter(t -> key.equals(t.key()))
                .map(Tag::value).findFirst().orElse("");
    }

    private static String effectiveEnvName(EnvironmentConfig e) {
        return (e.name() != null && !e.name().isBlank()) ? e.name() : e.type();
    }

    private static String arnToName(String arn) {
        if (arn == null) return "unknown";
        int slash = arn.lastIndexOf('/');
        return slash >= 0 ? arn.substring(slash + 1) : arn;
    }

    private static String toId(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String id = name.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");
        // Identifiers must not start with a digit
        if (!id.isEmpty() && Character.isDigit(id.charAt(0))) id = "r_" + id;
        return id.isBlank() ? "unknown" : id;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "'");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? "…" + s.substring(s.length() - (max - 1)) : s;
    }

    private void failJob(JobRecord job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(reason);
        jobStore.update(job);
        LOG.errorf("GenerateCloudArchitecture job %s failed: %s", job.getJobId(), reason);
    }

    // ── Internal data classes ─────────────────────────────────────────────────

    record EcsClusterInfo(String name, String arn, List<EcsServiceInfo> services) {}
    record EcsServiceInfo(String name, String image, String launchType, int desiredCount) {}
    record RdsInstanceInfo(String id, String engine, String instanceClass, boolean multiAz, String vpcId) {}

    static class DiscoveryResult {
        List<Vpc>                  vpcs               = new ArrayList<>();
        List<Subnet>               subnets            = new ArrayList<>();
        List<Instance>             instances          = new ArrayList<>();
        List<SecurityGroup>        securityGroups     = new ArrayList<>();
        List<NatGateway>           natGateways        = new ArrayList<>();
        List<InternetGateway>      internetGateways   = new ArrayList<>();
        List<EcsClusterInfo>       ecsClusters        = new ArrayList<>();
        List<RdsInstanceInfo>      rdsInstances       = new ArrayList<>();
        List<DBCluster>            rdsClusters        = new ArrayList<>();
        List<LoadBalancer>         loadBalancers      = new ArrayList<>();
        List<CacheCluster>         cacheClusters      = new ArrayList<>();
        List<ReplicationGroup>     replicationGroups  = new ArrayList<>();
        List<FunctionConfiguration>  lambdaFunctions   = new ArrayList<>();
        List<Bucket>                 s3Buckets         = new ArrayList<>();
        List<DistributionSummary>    cfDistributions   = new ArrayList<>();
        /** resourceId (service name / instance name / db id) → subnetId */
        Map<String, String>          resourceSubnetMap = new HashMap<>();
        /** ENI id → subnetId (used to resolve ECS task placement) */
        Map<String, String>          eniSubnetMap      = new HashMap<>();

        boolean isEmpty() {
            return vpcs.isEmpty() && ecsClusters.isEmpty() && rdsInstances.isEmpty()
                    && loadBalancers.isEmpty() && lambdaFunctions.isEmpty()
                    && instances.isEmpty() && cacheClusters.isEmpty()
                    && cfDistributions.isEmpty();
        }
    }
}
