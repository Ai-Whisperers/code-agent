package com.eneve.agent.aws;

import com.eneve.agent.aws.AwsDiscoveryResult.EcsClusterInfo;
import com.eneve.agent.aws.AwsDiscoveryResult.EcsServiceInfo;
import com.eneve.agent.aws.AwsDiscoveryResult.RdsInstanceInfo;
import com.eneve.agent.model.CloudAccount;
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
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
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
import software.amazon.awssdk.services.elasticache.model.DescribeCacheSubnetGroupsRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsRequest;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionsRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsRequest;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Performs a broad read-only discovery pass across a customer's AWS account.
 * Each service section is isolated so a failure in one does not abort others.
 */
@ApplicationScoped
public class AwsResourceDiscoverer {

    private static final Logger LOG = Logger.getLogger(AwsResourceDiscoverer.class);

    @Inject
    AwsClientFactory clientFactory;

    public AwsDiscoveryResult discover(String roleArn, String region, CloudAccount cloudAccount) {
        AwsDiscoveryResult result = new AwsDiscoveryResult();
        discoverEc2AndNetwork(result, roleArn, region, cloudAccount);
        discoverEcs(result, roleArn, region, cloudAccount);
        discoverRds(result, roleArn, region, cloudAccount);
        discoverElb(result, roleArn, region, cloudAccount);
        discoverElastiCache(result, roleArn, region, cloudAccount);
        discoverLambda(result, roleArn, region, cloudAccount);
        discoverS3(result, roleArn, region, cloudAccount);
        discoverCloudFront(result, roleArn, cloudAccount);
        return result;
    }

    // ── EC2 / VPC / networking ────────────────────────────────────────────────

    private void discoverEc2AndNetwork(AwsDiscoveryResult d, String roleArn, String region,
                                       CloudAccount cloudAccount) {
        try (Ec2Client ec2 = clientFactory.ec2Client(roleArn, region, cloudAccount)) {
            d.vpcs = ec2.describeVpcs(DescribeVpcsRequest.builder().build()).vpcs();
            d.subnets = ec2.describeSubnets(DescribeSubnetsRequest.builder().build()).subnets();
            d.instances = ec2.describeInstances(DescribeInstancesRequest.builder().build())
                    .reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .filter(i -> !"terminated".equals(i.state().nameAsString()))
                    .collect(Collectors.toList());

            for (Instance inst : d.instances) {
                String name = AwsSdkUtils.tagValue(inst.tags(), "Name");
                String key  = name.isBlank() ? inst.instanceId() : name;
                if (inst.subnetId() != null && !inst.subnetId().isBlank()) {
                    d.resourceSubnetMap.put(key, inst.subnetId());
                }
            }

            d.securityGroups = ec2.describeSecurityGroups(
                    DescribeSecurityGroupsRequest.builder().build()).securityGroups();

            try {
                d.natGateways = ec2.describeNatGateways(
                        DescribeNatGatewaysRequest.builder().build()).natGateways();
            } catch (Exception e) {
                LOG.debugf("NAT gateway discovery skipped: %s", e.getMessage());
            }
            try {
                d.internetGateways = ec2.describeInternetGateways(
                        DescribeInternetGatewaysRequest.builder().build()).internetGateways();
            } catch (Exception e) {
                LOG.debugf("Internet gateway discovery skipped: %s", e.getMessage());
            }
            try {
                List<NetworkInterface> enis = ec2.describeNetworkInterfaces(
                        DescribeNetworkInterfacesRequest.builder().build()).networkInterfaces();
                for (NetworkInterface eni : enis) {
                    if (eni.networkInterfaceId() != null && eni.subnetId() != null) {
                        d.eniSubnetMap.put(eni.networkInterfaceId(), eni.subnetId());
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ENI discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("EC2/VPC discovery failed: %s", e.getMessage());
        }
    }

    // ── ECS ───────────────────────────────────────────────────────────────────

    private void discoverEcs(AwsDiscoveryResult d, String roleArn, String region,
                             CloudAccount cloudAccount) {
        try (EcsClient ecs = clientFactory.ecsClient(roleArn, region, cloudAccount)) {
            List<String> clusterArns = listAllClusterArns(ecs);
            for (String clusterArn : clusterArns) {
                String clusterName = AwsSdkUtils.arnToName(clusterArn);
                List<EcsServiceInfo> services = new ArrayList<>();
                List<String> serviceArns = listAllServiceArns(ecs, clusterArn);
                if (!serviceArns.isEmpty()) {
                    // describeServices accepts at most 10 ARNs per call
                    for (int i = 0; i < serviceArns.size(); i += 10) {
                        List<String> batch = serviceArns.subList(i, Math.min(i + 10, serviceArns.size()));
                        for (Service svc : ecs.describeServices(DescribeServicesRequest.builder()
                                .cluster(clusterArn).services(batch).build()).services()) {
                            String image = resolveImage(ecs, svc.taskDefinition());
                            services.add(new EcsServiceInfo(svc.serviceName(), image,
                                    svc.launchTypeAsString(), svc.desiredCount()));
                        }
                    }
                }
                resolveEcsTaskSubnets(ecs, d, clusterArn, clusterName, services);
                d.ecsClusters.add(new EcsClusterInfo(clusterName, clusterArn, services));
            }
        } catch (Exception e) {
            LOG.warnf("ECS discovery failed: %s", e.getMessage());
        }
    }

    private List<String> listAllClusterArns(EcsClient ecs) {
        List<String> arns = new ArrayList<>();
        String nextToken = null;
        do {
            var req = ListClustersRequest.builder().maxResults(100);
            if (nextToken != null) req.nextToken(nextToken);
            var resp = ecs.listClusters(req.build());
            arns.addAll(resp.clusterArns());
            nextToken = resp.nextToken();
        } while (nextToken != null);
        return arns;
    }

    private List<String> listAllServiceArns(EcsClient ecs, String clusterArn) {
        List<String> arns = new ArrayList<>();
        String nextToken = null;
        do {
            var req = ListServicesRequest.builder().cluster(clusterArn).maxResults(100);
            if (nextToken != null) req.nextToken(nextToken);
            var resp = ecs.listServices(req.build());
            arns.addAll(resp.serviceArns());
            nextToken = resp.nextToken();
        } while (nextToken != null);
        return arns;
    }

    private void resolveEcsTaskSubnets(EcsClient ecs, AwsDiscoveryResult d,
                                       String clusterArn, String clusterName,
                                       List<EcsServiceInfo> services) {
        try {
            List<String> taskArns = new ArrayList<>();
            String nextToken = null;
            do {
                var req = ListTasksRequest.builder().cluster(clusterArn).maxResults(100);
                if (nextToken != null) req.nextToken(nextToken);
                var resp = ecs.listTasks(req.build());
                taskArns.addAll(resp.taskArns());
                nextToken = resp.nextToken();
            } while (nextToken != null);
            if (taskArns.isEmpty()) return;

            List<Task> tasks = ecs.describeTasks(
                    DescribeTasksRequest.builder().cluster(clusterArn).tasks(taskArns).build()
            ).tasks();

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
                            String subnet = d.eniSubnetMap.get(detail.value());
                            if (subnet != null) svcSubnet.putIfAbsent(svcName, subnet);
                        }
                    }
                }
            }
            for (EcsServiceInfo svc : services) {
                String subnet = svcSubnet.get(svc.name());
                if (subnet != null) d.resourceSubnetMap.put(svc.name(), subnet);
            }
        } catch (Exception e) {
            LOG.debugf("ECS task ENI resolution skipped for cluster %s: %s", clusterName, e.getMessage());
        }
    }

    // ── RDS ───────────────────────────────────────────────────────────────────

    private void discoverRds(AwsDiscoveryResult d, String roleArn, String region,
                             CloudAccount cloudAccount) {
        try (RdsClient rds = clientFactory.rdsClient(roleArn, region, cloudAccount)) {
            rds.describeDBInstances(DescribeDbInstancesRequest.builder().build()).dbInstances()
                    .forEach(db -> {
                        String firstSubnet = null;
                        if (db.dbSubnetGroup() != null && db.dbSubnetGroup().subnets() != null
                                && !db.dbSubnetGroup().subnets().isEmpty()) {
                            firstSubnet = db.dbSubnetGroup().subnets().get(0).subnetIdentifier();
                        }
                        d.rdsInstances.add(new RdsInstanceInfo(
                                db.dbInstanceIdentifier(), db.engine(),
                                db.dbInstanceClass(), db.multiAZ(),
                                db.dbSubnetGroup() != null ? db.dbSubnetGroup().vpcId() : null));
                        if (firstSubnet != null) {
                            d.resourceSubnetMap.put(db.dbInstanceIdentifier(), firstSubnet);
                        }
                    });
            try {
                rds.describeDBClusters(DescribeDbClustersRequest.builder().build()).dbClusters()
                        .forEach(c -> {
                            d.rdsClusters.add(c);
                            // Resolve the subnet group name to an actual subnet ID so that
                            // CloudArchitectureDslBuilder can place the cluster in the correct
                            // VPC deployment node (resourceSubnetMap values must be subnet IDs).
                            if (c.dbSubnetGroup() != null && !c.dbSubnetGroup().isBlank()) {
                                try {
                                    var subnetGroupResp = rds.describeDBSubnetGroups(
                                            DescribeDbSubnetGroupsRequest.builder()
                                                    .dbSubnetGroupName(c.dbSubnetGroup())
                                                    .build());
                                    subnetGroupResp.dbSubnetGroups().stream()
                                            .filter(sg -> sg.subnets() != null && !sg.subnets().isEmpty())
                                            .findFirst()
                                            .ifPresent(sg -> d.resourceSubnetMap.putIfAbsent(
                                                    c.dbClusterIdentifier(),
                                                    sg.subnets().get(0).subnetIdentifier()));
                                } catch (Exception ex) {
                                    LOG.debugf("Could not resolve subnet for RDS cluster %s: %s",
                                            c.dbClusterIdentifier(), ex.getMessage());
                                }
                            }
                        });
            } catch (Exception e) {
                LOG.debugf("RDS cluster discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("RDS discovery failed: %s", e.getMessage());
        }
    }

    // ── ELB ───────────────────────────────────────────────────────────────────

    private void discoverElb(AwsDiscoveryResult d, String roleArn, String region,
                             CloudAccount cloudAccount) {
        try (ElasticLoadBalancingV2Client elb = clientFactory.elbV2Client(roleArn, region, cloudAccount)) {
            d.loadBalancers = elb.describeLoadBalancers(
                    DescribeLoadBalancersRequest.builder().build()).loadBalancers();
        } catch (Exception e) {
            LOG.warnf("ELB discovery failed: %s", e.getMessage());
        }
    }

    // ── ElastiCache ───────────────────────────────────────────────────────────

    private void discoverElastiCache(AwsDiscoveryResult d, String roleArn, String region,
                                     CloudAccount cloudAccount) {
        try (ElastiCacheClient ec = clientFactory.elastiCacheClient(roleArn, region, cloudAccount)) {
            d.cacheClusters = ec.describeCacheClusters(
                    DescribeCacheClustersRequest.builder().showCacheNodeInfo(true).build()
            ).cacheClusters();

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
                for (CacheCluster cc : d.cacheClusters) {
                    if (cc.cacheSubnetGroupName() != null) {
                        String subnet = groupToSubnet.get(cc.cacheSubnetGroupName());
                        if (subnet != null) d.resourceSubnetMap.put(cc.cacheClusterId(), subnet);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ElastiCache subnet group resolution skipped: %s", e.getMessage());
            }

            try {
                d.replicationGroups = ec.describeReplicationGroups(
                        DescribeReplicationGroupsRequest.builder().build()).replicationGroups();
                for (ReplicationGroup rg : d.replicationGroups) {
                    if (rg.memberClusters() != null && !rg.memberClusters().isEmpty()) {
                        String firstMember = rg.memberClusters().get(0);
                        String subnet = d.resourceSubnetMap.get(firstMember);
                        if (subnet != null) d.resourceSubnetMap.putIfAbsent(rg.replicationGroupId(), subnet);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("ElastiCache replication group discovery skipped: %s", e.getMessage());
            }
        } catch (Exception e) {
            LOG.warnf("ElastiCache discovery failed: %s", e.getMessage());
        }
    }

    // ── Lambda ────────────────────────────────────────────────────────────────

    private void discoverLambda(AwsDiscoveryResult d, String roleArn, String region,
                                CloudAccount cloudAccount) {
        try (LambdaClient lambda = clientFactory.lambdaClient(roleArn, region, cloudAccount)) {
            String marker = null;
            do {
                var req = ListFunctionsRequest.builder().maxItems(50);
                if (marker != null) req.marker(marker);
                var resp = lambda.listFunctions(req.build());
                d.lambdaFunctions.addAll(resp.functions());
                marker = resp.nextMarker();
            } while (marker != null);

            for (FunctionConfiguration fn : d.lambdaFunctions) {
                if (fn.vpcConfig() != null && fn.vpcConfig().subnetIds() != null
                        && !fn.vpcConfig().subnetIds().isEmpty()) {
                    d.resourceSubnetMap.put(fn.functionName(),
                            fn.vpcConfig().subnetIds().get(0));
                }
            }
        } catch (Exception e) {
            LOG.warnf("Lambda discovery failed: %s", e.getMessage());
        }
    }

    // ── S3 ────────────────────────────────────────────────────────────────────

    private void discoverS3(AwsDiscoveryResult d, String roleArn, String region,
                            CloudAccount cloudAccount) {
        try (S3Client s3 = clientFactory.s3Client(roleArn, region, cloudAccount)) {
            d.s3Buckets = s3.listBuckets().buckets();
        } catch (Exception e) {
            LOG.warnf("S3 discovery failed: %s", e.getMessage());
        }
    }

    // ── CloudFront ────────────────────────────────────────────────────────────

    private void discoverCloudFront(AwsDiscoveryResult d, String roleArn, CloudAccount cloudAccount) {
        try (CloudFrontClient cf = clientFactory.cloudFrontClient(roleArn, cloudAccount)) {
            String marker = null;
            do {
                var req = ListDistributionsRequest.builder();
                if (marker != null) req.marker(marker);
                var resp = cf.listDistributions(req.build());
                if (resp.distributionList() != null) {
                    d.cfDistributions.addAll(resp.distributionList().items());
                    marker = resp.distributionList().isTruncated()
                            ? resp.distributionList().nextMarker() : null;
                } else {
                    marker = null;
                }
            } while (marker != null);
        } catch (Exception e) {
            LOG.warnf("CloudFront discovery failed: %s", e.getMessage());
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

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

}
