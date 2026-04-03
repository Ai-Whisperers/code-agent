package com.eneve.agent.aws;

import com.eneve.agent.aws.AwsDiscoveryResult.EcsClusterInfo;
import com.eneve.agent.aws.AwsDiscoveryResult.EcsServiceInfo;
import com.eneve.agent.aws.AwsDiscoveryResult.RdsInstanceInfo;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a Structurizr DSL workspace from an {@link AwsDiscoveryResult}.
 * Produces three views: SystemContext, Containers, and Deployment.
 */
@ApplicationScoped
public class CloudArchitectureDslBuilder {

    public String build(String customerName, String environmentName, String region,
                        AwsDiscoveryResult d, String pinnedDsl) {
        String humanElements = extractHumanElements(pinnedDsl);
        String systemId = toId(customerName);
        String envLabel = escape(environmentName);

        StringBuilder sb = new StringBuilder();
        sb.append("workspace \"").append(escape(customerName)).append(" — ")
          .append(envLabel).append("\" {\n\n");
        sb.append("    !identifiers hierarchical\n\n");
        sb.append("    model {\n\n");

        appendExternalActors(sb);
        appendMainSystem(sb, systemId, customerName, envLabel, region, d, humanElements);
        appendRelationships(sb, systemId, d);
        appendDeploymentEnvironment(sb, systemId, envLabel, d);

        sb.append("\n    }\n\n");
        appendViews(sb, systemId, customerName, envLabel, d);
        sb.append("}\n");

        return sb.toString();
    }

    // ── Model sections ────────────────────────────────────────────────────────

    private void appendExternalActors(StringBuilder sb) {
        sb.append("        user = person \"End User\" \"External user of the system\"\n");
        sb.append("        internet = softwareSystem \"Internet\" \"Public internet\" {\n");
        sb.append("            tags \"External\"\n");
        sb.append("        }\n\n");
    }

    private void appendMainSystem(StringBuilder sb, String systemId, String customerName,
                                  String envLabel, String region, AwsDiscoveryResult d,
                                  String humanElements) {
        sb.append("        ").append(systemId).append(" = softwareSystem \"")
          .append(escape(customerName)).append("\" \"AWS-hosted system in ")
          .append(envLabel).append(" (").append(region).append(")\" {\n\n");

        appendCloudFrontContainers(sb, d);
        appendLoadBalancerContainers(sb, d);
        appendEcsContainers(sb, d);
        appendEc2Containers(sb, d);
        appendRdsContainers(sb, d);
        appendElastiCacheContainers(sb, d);
        appendLambdaContainers(sb, d);
        appendS3Containers(sb, d);

        if (!humanElements.isBlank()) {
            sb.append("\n            // Human-added elements (preserved from pinned version)\n");
            sb.append(humanElements).append("\n");
        }

        sb.append("        }\n\n");
    }

    private void appendCloudFrontContainers(StringBuilder sb, AwsDiscoveryResult d) {
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
    }

    private void appendLoadBalancerContainers(StringBuilder sb, AwsDiscoveryResult d) {
        for (LoadBalancer lb : d.loadBalancers) {
            String lbId   = toId(lb.loadBalancerName());
            String lbType = lb.typeAsString() != null ? lb.typeAsString().toUpperCase() : "ALB";
            sb.append("            ").append(lbId).append(" = container \"")
              .append(escape(lb.loadBalancerName())).append("\" \"")
              .append(lbType).append(" load balancer\" \"AWS ").append(lbType).append("\" {\n");
            sb.append("                tags \"LoadBalancer\"\n");
            sb.append("            }\n");
        }
    }

    private void appendEcsContainers(StringBuilder sb, AwsDiscoveryResult d) {
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
    }

    private void appendEc2Containers(StringBuilder sb, AwsDiscoveryResult d) {
        Map<String, List<Instance>> ec2ByName = d.instances.stream()
                .collect(Collectors.groupingBy(i -> {
                    String name = AwsResourceDiscoverer.tagValue(i.tags(), "Name");
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
    }

    private void appendRdsContainers(StringBuilder sb, AwsDiscoveryResult d) {
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
        Set<String> rdsInstanceIds = d.rdsInstances.stream()
                .map(RdsInstanceInfo::id).collect(Collectors.toSet());
        for (DBCluster cluster : d.rdsClusters) {
            if (rdsInstanceIds.contains(cluster.dbClusterIdentifier())) continue;
            String cId  = toId(cluster.dbClusterIdentifier());
            String tech = cluster.engine() != null ? cluster.engine() : "Aurora";
            sb.append("            ").append(cId).append(" = container \"")
              .append(escape(cluster.dbClusterIdentifier())).append("\" \"Aurora cluster\" \"")
              .append(tech).append("\" {\n");
            sb.append("                tags \"Database\"\n");
            sb.append("            }\n");
        }
    }

    private void appendElastiCacheContainers(StringBuilder sb, AwsDiscoveryResult d) {
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
    }

    private void appendLambdaContainers(StringBuilder sb, AwsDiscoveryResult d) {
        if (!d.lambdaFunctions.isEmpty()) {
            sb.append("\n            // Lambda Functions\n");
            for (FunctionConfiguration fn : d.lambdaFunctions) {
                String fnId    = toId(fn.functionName());
                String runtime = fn.runtimeAsString() != null ? fn.runtimeAsString() : "Lambda";
                String desc    = fn.description() != null && !fn.description().isBlank()
                        ? fn.description() : "Lambda function";
                sb.append("            ").append(fnId).append(" = container \"")
                  .append(escape(fn.functionName())).append("\" \"").append(escape(truncate(desc, 80)))
                  .append("\" \"").append(runtime).append("\" {\n");
                sb.append("                tags \"Lambda\"\n");
                sb.append("            }\n");
            }
        }
    }

    private void appendS3Containers(StringBuilder sb, AwsDiscoveryResult d) {
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
    }

    // ── Relationships ─────────────────────────────────────────────────────────

    private void appendRelationships(StringBuilder sb, String systemId, AwsDiscoveryResult d) {
        if (!d.cfDistributions.isEmpty()) {
            for (DistributionSummary dist : d.cfDistributions) {
                sb.append("        user -> ").append(systemId).append(".")
                  .append(toId(dist.id())).append(" \"Requests via\" \"HTTPS\"\n");
            }
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

        for (EcsClusterInfo cluster : d.ecsClusters) {
            for (EcsServiceInfo svc : cluster.services()) {
                for (RdsInstanceInfo rds : d.rdsInstances) {
                    sb.append("        ").append(systemId).append(".").append(toId(svc.name()))
                      .append(" -> ").append(systemId).append(".").append(toId(rds.id()))
                      .append(" \"Reads/writes\" \"JDBC\"\n");
                }
                for (ReplicationGroup rg : d.replicationGroups) {
                    sb.append("        ").append(systemId).append(".").append(toId(svc.name()))
                      .append(" -> ").append(systemId).append(".").append(toId(rg.replicationGroupId()))
                      .append(" \"Caches via\" \"Redis\"\n");
                }
            }
        }
    }

    // ── Deployment environment ────────────────────────────────────────────────

    private void appendDeploymentEnvironment(StringBuilder sb, String systemId,
                                             String envLabel, AwsDiscoveryResult d) {
        if (d.vpcs.isEmpty()) return;

        Map<String, Subnet> subnetById = d.subnets.stream()
                .collect(Collectors.toMap(Subnet::subnetId, s -> s, (a, b) -> a));

        sb.append("\n        deploymentEnvironment \"").append(envLabel).append("\" {\n");

        appendGlobalDeploymentNodes(sb, systemId, d);

        for (Vpc vpc : d.vpcs) {
            appendVpcDeploymentNode(sb, systemId, vpc, d, subnetById);
        }

        sb.append("        }\n");
    }

    private void appendGlobalDeploymentNodes(StringBuilder sb, String systemId, AwsDiscoveryResult d) {
        for (DistributionSummary dist : d.cfDistributions) {
            sb.append("            deploymentNode \"CloudFront\" \"AWS Global CDN\" \"AWS CloudFront\" {\n");
            sb.append("                containerInstance ").append(systemId).append(".")
              .append(toId(dist.id())).append("\n");
            sb.append("            }\n");
        }

        if (!d.s3Buckets.isEmpty()) {
            sb.append("            deploymentNode \"S3\" \"Object storage\" \"AWS S3\" {\n");
            for (Bucket bucket : d.s3Buckets) {
                sb.append("                containerInstance ").append(systemId).append(".")
                  .append(toId(bucket.name())).append("\n");
            }
            sb.append("            }\n");
        }

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
    }

    private void appendVpcDeploymentNode(StringBuilder sb, String systemId, Vpc vpc,
                                         AwsDiscoveryResult d, Map<String, Subnet> subnetById) {
        String vpcName = AwsResourceDiscoverer.tagValue(vpc.tags(), "Name");
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

        Map<String, List<String>> subnetInstances = new LinkedHashMap<>();
        List<String> unplacedInstances = new ArrayList<>();

        java.util.function.BiConsumer<String, String> place = (resourceKey, containerId) -> {
            String subnetId = d.resourceSubnetMap.get(resourceKey);
            if (subnetId != null && subnetById.containsKey(subnetId)) {
                subnetInstances.computeIfAbsent(subnetId, k -> new ArrayList<>()).add(containerId);
            } else {
                unplacedInstances.add(containerId);
            }
        };

        for (LoadBalancer lb : d.loadBalancers) {
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

        for (EcsClusterInfo cluster : d.ecsClusters) {
            for (EcsServiceInfo svc : cluster.services()) {
                place.accept(svc.name(), systemId + "." + toId(svc.name()));
            }
        }

        for (Instance inst : d.instances) {
            if (!vpc.vpcId().equals(inst.vpcId())) continue;
            String name = AwsResourceDiscoverer.tagValue(inst.tags(), "Name");
            String key  = name.isBlank() ? inst.instanceId() : name;
            place.accept(key, systemId + "." + toId(key));
        }

        for (RdsInstanceInfo rds : d.rdsInstances) {
            if (!vpc.vpcId().equals(rds.vpcId())) continue;
            place.accept(rds.id(), systemId + "." + toId(rds.id()));
        }

        Set<String> deployRdsInstanceIds = d.rdsInstances.stream()
                .map(RdsInstanceInfo::id).collect(Collectors.toSet());
        for (DBCluster cluster : d.rdsClusters) {
            if (deployRdsInstanceIds.contains(cluster.dbClusterIdentifier())) continue;
            place.accept(cluster.dbClusterIdentifier(),
                    systemId + "." + toId(cluster.dbClusterIdentifier()));
        }

        Set<String> seenGroups = new HashSet<>();
        for (ReplicationGroup rg : d.replicationGroups) {
            seenGroups.add(rg.replicationGroupId());
            place.accept(rg.replicationGroupId(), systemId + "." + toId(rg.replicationGroupId()));
        }
        for (CacheCluster cc : d.cacheClusters) {
            if (cc.replicationGroupId() != null && seenGroups.contains(cc.replicationGroupId())) continue;
            place.accept(cc.cacheClusterId(), systemId + "." + toId(cc.cacheClusterId()));
        }

        for (FunctionConfiguration fn : d.lambdaFunctions) {
            if (fn.vpcConfig() == null || fn.vpcConfig().subnetIds().isEmpty()) continue;
            boolean inThisVpc = fn.vpcConfig().subnetIds().stream()
                    .anyMatch(sid -> subnetById.containsKey(sid)
                            && vpc.vpcId().equals(subnetById.get(sid).vpcId()));
            if (!inThisVpc) continue;
            place.accept(fn.functionName(), systemId + "." + toId(fn.functionName()));
        }

        for (Map.Entry<String, List<String>> entry : subnetInstances.entrySet()) {
            Subnet subnet = subnetById.get(entry.getKey());
            String subnetName = AwsResourceDiscoverer.tagValue(subnet.tags(), "Name");
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

        if (!unplacedInstances.isEmpty()) {
            sb.append("                deploymentNode \"Other\" \"Resources without subnet info\" \"AWS\" {\n");
            for (String cid : unplacedInstances) {
                sb.append("                    containerInstance ").append(cid).append("\n");
            }
            sb.append("                }\n");
        }

        sb.append("            }\n");
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private void appendViews(StringBuilder sb, String systemId, String customerName,
                             String envLabel, AwsDiscoveryResult d) {
        sb.append("    views {\n\n");

        sb.append("        systemContext ").append(systemId).append(" \"SystemContext\" {\n");
        sb.append("            include *\n");
        sb.append("            title \"").append(escape(customerName)).append(" — System Context (")
          .append(envLabel).append(")\"\n");
        sb.append("        }\n\n");

        sb.append("        container ").append(systemId).append(" \"Containers\" {\n");
        sb.append("            include *\n");
        sb.append("            title \"").append(escape(customerName)).append(" — Containers (")
          .append(envLabel).append(")\"\n");
        sb.append("        }\n\n");

        if (!d.vpcs.isEmpty()) {
            sb.append("        deployment * \"").append(envLabel).append("\" \"Deployment\" {\n");
            sb.append("            include *\n");
            sb.append("            title \"").append(escape(customerName))
              .append(" — Deployment (").append(envLabel).append(")\"\n");
            sb.append("        }\n\n");
        }

        appendStyles(sb);

        sb.append("    }\n");
    }

    private void appendStyles(StringBuilder sb) {
        sb.append("        styles {\n");
        sb.append("            element \"Person\" {\n                shape Person\n            }\n");
        sb.append("            element \"Database\" {\n                shape Cylinder\n            }\n");
        sb.append("            element \"Cache\" {\n                shape Cylinder\n                background #FF6B6B\n                color #ffffff\n            }\n");
        sb.append("            element \"ECS\" {\n                background #2196F3\n                color #ffffff\n            }\n");
        sb.append("            element \"EC2\" {\n                background #FF9800\n                color #ffffff\n            }\n");
        sb.append("            element \"Lambda\" {\n                background #9C27B0\n                color #ffffff\n                shape Component\n            }\n");
        sb.append("            element \"CDN\" {\n                background #00BCD4\n                color #ffffff\n                shape Hexagon\n            }\n");
        sb.append("            element \"LoadBalancer\" {\n                background #4CAF50\n                color #ffffff\n                shape Hexagon\n            }\n");
        sb.append("            element \"Storage\" {\n                shape Cylinder\n                background #795548\n                color #ffffff\n            }\n");
        sb.append("            element \"External\" {\n                background #999999\n                color #ffffff\n            }\n");
        sb.append("        }\n\n");
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String extractHumanElements(String pinnedDsl) {
        if (pinnedDsl == null || pinnedDsl.isBlank()) return "";
        return pinnedDsl.lines()
                .filter(l -> l.contains("!human"))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    static String toId(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String id = name.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_")
                        .replaceAll("^_|_$", "");
        if (!id.isEmpty() && Character.isDigit(id.charAt(0))) id = "r_" + id;
        return id.isBlank() ? "unknown" : id;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "'");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? "…" + s.substring(s.length() - (max - 1)) : s;
    }
}
