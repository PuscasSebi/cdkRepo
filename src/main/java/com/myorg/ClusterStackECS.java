package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ClusterProps;
import software.amazon.awscdk.services.ecs.ContainerInsights;
import software.constructs.Construct;

public class ClusterStackECS extends Stack {

    private final Cluster cluster;;

    public ClusterStackECS(Construct scope, String id, StackProps props,
                           ClusterStackProps clusterStackProps) {
        super(scope, id, props);

        this.cluster = new Cluster(this, "Cluster",
                ClusterProps.builder().clusterName("ECommers")
                        .vpc( clusterStackProps.vpc())
                        .containerInsightsV2(ContainerInsights.ENABLED)
                        .build());

    }
}

record ClusterStackProps(Vpc vpc){}
