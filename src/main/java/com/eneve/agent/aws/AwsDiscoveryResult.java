package com.eneve.agent.aws;

import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InternetGateway;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated result of a read-only AWS resource discovery pass.
 */
public class AwsDiscoveryResult {

    public List<Vpc>                   vpcs               = new ArrayList<>();
    public List<Subnet>                subnets            = new ArrayList<>();
    public List<Instance>              instances          = new ArrayList<>();
    public List<SecurityGroup>         securityGroups     = new ArrayList<>();
    public List<NatGateway>            natGateways        = new ArrayList<>();
    public List<InternetGateway>       internetGateways   = new ArrayList<>();
    public List<EcsClusterInfo>        ecsClusters        = new ArrayList<>();
    public List<RdsInstanceInfo>       rdsInstances       = new ArrayList<>();
    public List<DBCluster>             rdsClusters        = new ArrayList<>();
    public List<LoadBalancer>          loadBalancers      = new ArrayList<>();
    public List<CacheCluster>          cacheClusters      = new ArrayList<>();
    public List<ReplicationGroup>      replicationGroups  = new ArrayList<>();
    public List<FunctionConfiguration> lambdaFunctions    = new ArrayList<>();
    public List<Bucket>                s3Buckets          = new ArrayList<>();
    public List<DistributionSummary>   cfDistributions    = new ArrayList<>();

    /** resourceId (service name / instance name / db id) → subnetId */
    public Map<String, String> resourceSubnetMap = new HashMap<>();

    /** ENI id → subnetId (used to resolve ECS task placement) */
    public Map<String, String> eniSubnetMap = new HashMap<>();

    public boolean isEmpty() {
        return vpcs.isEmpty() && ecsClusters.isEmpty() && rdsInstances.isEmpty()
                && loadBalancers.isEmpty() && lambdaFunctions.isEmpty()
                && instances.isEmpty() && cacheClusters.isEmpty()
                && cfDistributions.isEmpty();
    }

    public record EcsClusterInfo(String name, String arn, List<EcsServiceInfo> services) {}
    public record EcsServiceInfo(String name, String image, String launchType, int desiredCount) {}
    public record RdsInstanceInfo(String id, String engine, String instanceClass, boolean multiAz, String vpcId) {}
}
