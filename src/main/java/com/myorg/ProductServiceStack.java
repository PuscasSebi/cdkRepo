package com.myorg;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.Protocol;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscriptionProps;
import software.constructs.Construct;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class ProductServiceStack extends Stack {

    @Getter
    private final Topic productEventsTopic;

    public ProductServiceStack(Construct scope, String id, StackProps props,
                               ProductServiceStackProps productStack) {
        super(scope, id, props);

        productEventsTopic = new Topic(this, "ProductEventsTopic", TopicProps.builder()
                .displayName("Product events topic")
                .topicName("product-events")
                .build());

        //for testing only remove prod
        productEventsTopic.addSubscription(new EmailSubscription("puscas.sebastian@gmail.com",
                EmailSubscriptionProps.builder()
                        .json(true).build()
                ));

        Table productDb = new Table(this,"productsDbd",
                TableProps.builder()
                        .partitionKey(Attribute.builder()
                                .name("id")
                                .type(AttributeType.STRING)
                                .build())
                        .tableName("products")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .billingMode(BillingMode.PROVISIONED)
                        .readCapacity(1)
                        .writeCapacity(1)
                        .build()
                ); //won't be charged until usage

        productDb.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                        .indexName("codeIdx")
                        .partitionKey(Attribute.builder()
                                .name("code")
                                .type(AttributeType.STRING)
                                .build())
                        .projectionType(ProjectionType.KEYS_ONLY)
                        .readCapacity(1)
                        .writeCapacity(1)
                .build());


        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this,
                "FargateTaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("product-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build()
        );

        productDb.grantReadWriteData(taskDefinition.getTaskRole());
        productEventsTopic.grantPublish(taskDefinition.getTaskRole());

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
                "AWS_PRODUCTSDB_NAME", productDb.getTableName(),
                "AWS_REGION", this.getRegion(),
                "SERVER_PORT", String.valueOf(appPort),
                "AWS_SNS_TOPIC_PRODUCT_EVENTS", this.productEventsTopic.getTopicArn(),
                "AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000",
                "AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR",
                "AWS_XRAY_TRACING_NAME", "productservice",
                "LOGGING_LEVEL_ROOT", "INFO",
                "ORG_APACHE_HTTP_WIRE", "TRACE"
                //org.apache.http.wire"
        );
        taskDefinition.addContainer("ProductServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(productStack.repository(), "7.0.0"))
                        .containerName("productsService")
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(appPort)
                                        .protocol(software.amazon.awscdk.services.ecs.Protocol.TCP)
                                .build()))
                        .logging(logDriver)
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .environment(environment)
                        .build());


        taskDefinition.addContainer("Xray", ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                        .containerName("XrayProductsService")
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                        .containerPort(2000)
                                        .protocol(software.amazon.awscdk.services.ecs.Protocol.UDP)
                                .build()))
                        .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                                .logGroup(new LogGroup(this, "xRayLogGroup",
                                        LogGroupProps.builder()
                                        .logGroupName("XrayProductService")
                                        .removalPolicy(RemovalPolicy.DESTROY)
                                        .retention(RetentionDays.FIVE_DAYS)
                                        .build()))
                                .streamPrefix("XRayProductService")
                                .build()))
                        .cpu(128)
                        .memoryLimitMiB(128)
                .build());

        taskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXRayWriteOnlyAccess"));
        ApplicationListener applicationListener = productStack.applicationLoadBalancer()
                .addListener("ApplicationListener", ApplicationListenerProps.builder()
                        .port(appPort)
                        .protocol(ApplicationProtocol.HTTP)
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
                                .protocol(Protocol.HTTP)

                                .healthyHttpCodes("200")
                                .port(String.valueOf(appPort))
                                .build())
                        .build());


        NetworkListener networkListener = productStack.networkLoadBalancer()
                .addListener("ProductServiceNLBListener",
                        NetworkListenerProps.builder()
                                .port(appPort)
                                .protocol(Protocol.TCP)
                                .loadBalancer(productStack.networkLoadBalancer())
                                .build()
                );
        networkListener.addTargets("productServiceNlbTargets",
                AddNetworkTargetsProps.builder()
                        .port(appPort)
                        .protocol(Protocol.TCP)
                        .targetGroupName("productServiceNlb")
                        .targets(Collections.singletonList(fargateService
                                .loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("productsService")
                                        .containerPort(appPort)
                                        .protocol(software.amazon.awscdk.services.ecs.Protocol.TCP)
                                        .build())
                        ))
                        .build());
    }
}
record ProductServiceStackProps(Vpc vpc, Cluster cluster,
                                NetworkLoadBalancer networkLoadBalancer,
                                ApplicationLoadBalancer applicationLoadBalancer,
                                Repository repository){}
