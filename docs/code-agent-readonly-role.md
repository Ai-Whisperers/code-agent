code-agent-readonly-role

Permission policy (attach to the `agent-readonly` role in each customer account):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:FilterLogEvents",
                "ecs:ListClusters",
                "ecs:DescribeClusters",
                "ecs:ListServices",
                "ecs:DescribeServices",
                "ecs:ListTasks",
                "ecs:DescribeTasks",
                "ecs:DescribeTaskDefinition",
                "cloudwatch:GetMetricStatistics",
                "cloudwatch:GetMetricData",
                "rds:DescribeDBInstances",
                "rds:DescribeDBClusters",
                "ec2:DescribeVpcs",
                "ec2:DescribeSubnets",
                "ec2:DescribeInstances",
                "ec2:DescribeSecurityGroups",
                "ec2:DescribeInternetGateways",
                "ec2:DescribeNatGateways",
                "ec2:DescribeNetworkInterfaces",
                "elasticloadbalancing:DescribeLoadBalancers",
                "elasticloadbalancing:DescribeTargetGroups",
                "elasticloadbalancing:DescribeListeners",
                "elasticache:DescribeCacheClusters",
                "elasticache:DescribeReplicationGroups",
                "elasticache:DescribeCacheSubnetGroups",
                "lambda:ListFunctions",
                "s3:ListAllMyBuckets",
                "cloudfront:ListDistributions"
            ],
            "Resource": "*"
        }
    ]
}
```

Trust policy (allows the code-agent AWS account to assume this role):

For AWS-nl (replace id for UK):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "AWS": "arn:aws:iam::450019303360:root"
            },
            "Action": "sts:AssumeRole"
        }
    ]
}
```
