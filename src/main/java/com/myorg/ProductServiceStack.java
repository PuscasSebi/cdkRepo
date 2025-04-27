package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.Protocol;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class ProductServiceStack extends Stack {

    public ProductServiceStack(Construct scope, String id, StackProps props,
                               ProductServiceStackProps productStack) {
        super(scope, id, props);

        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this,
                "FargateTaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("product-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build()
        );
        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup", LogGroupProps.builder()
                        .logGroupName("ProductService")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .retention(RetentionDays.FIVE_DAYS)
                        .build()))
                .streamPrefix("ProductService")
                .build());
        Integer appPort = 8080;
        Map<String,String> environment = Map.of(
                "SPRING_PROFILES_ACTIVE", "prod",
                "SERVER_PORT", String.valueOf(appPort)
        );
        taskDefinition.addContainer("ProductServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(productStack.repository(), "1.0.3"))
                        .containerName("productsService")
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(appPort)
                                        .protocol(software.amazon.awscdk.services.ecs.Protocol.TCP)
                                .build()))
                        .logging(logDriver)
                        .environment(environment)
                        .build());


        ApplicationListener applicationListener = productStack.applicationLoadBalancer()
                .addListener("ApplicationListener", ApplicationListenerProps.builder()
                        .port(appPort)
                        .protocol(ApplicationProtocol.HTTPS)
                        .loadBalancer(productStack.applicationLoadBalancer())
                        .build()
                );

        FargateService fargateService = new FargateService(this, "ProductsService",
                FargateServiceProps.builder()
                        .serviceName("ProductService")
                        .cluster(productStack.cluster())
                        .desiredCount(2)
                        .taskDefinition(taskDefinition)
                        .serviceName("ProductService")
                        .assignPublicIp(false)
                        .build()
        );
        productStack.repository().grantPull(Objects.requireNonNull(taskDefinition.getExecutionRole()));

        fargateService.getConnections().getSecurityGroups().get(0)
                .addIngressRule(Peer.anyIpv4(), Port.tcp(appPort));


        applicationListener.addTargets("ProductServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .port(appPort)
                        .targetGroupName("productServiceAlb")
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(20))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .protocol(Protocol.TCP)
                                .healthyHttpCodes("200")
                                .port(String.valueOf(appPort))
                                .build())
                        .build());


        NetworkListener networkListener = productStack.networkLoadBalancer()
                .addListener("ProductServiceNLBListener",
                        NetworkListenerProps.builder()
                                .port(appPort)
                                .protocol(Protocol.TCP)
                                .build()

                );
    }
}
record ProductServiceStackProps(Vpc vpc, Cluster cluster,
                                NetworkLoadBalancer networkLoadBalancer,
                                ApplicationLoadBalancer applicationLoadBalancer,
                                Repository repository){}
