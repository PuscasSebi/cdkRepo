package com.myorg;

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
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.StringConditions;
import software.amazon.awscdk.services.sns.SubscriptionFilter;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscriptionProps;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.sqs.QueueProps;
import software.constructs.Construct;

import java.util.*;

public class AuditServiceStack extends Stack {
    public AuditServiceStack(final Construct scope, final String id,
                             final StackProps props, AuditServiceProps auditServiceProps) {
        super(scope, id, props);

        Table eventsDb = new Table(this, "EventsDb",
                TableProps.builder()
                        .tableName("events")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .partitionKey(Attribute.builder()
                                .name("pk")
                                .type(AttributeType.STRING)
                                .build())
                        .sortKey(Attribute.builder()
                                .name("sk")
                                .type(AttributeType.STRING)
                                .build())
                        .timeToLiveAttribute("ttl")
                        .billingMode(BillingMode.PROVISIONED)
                        .readCapacity(1)
                        .writeCapacity(1)
                        .build()
                );

        Queue dlq = new Queue(this, "productEventsDLQ",
                QueueProps.builder()
                        .queueName("products-events-dql")
                        .retentionPeriod(Duration.days(3))
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .build());

        Queue productEventsQueue = new Queue(this, "ProductEventsQue",
                QueueProps.builder()
                        .queueName("product-events")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(dlq).maxReceiveCount(2)
                                .build())
                        .build()
                );

        Map<String, SubscriptionFilter> productsFilterPolicy = new HashMap<>();
        productsFilterPolicy.put(
                "eventType", SubscriptionFilter.stringFilter(StringConditions.builder()
                                .allowlist(Arrays.asList("PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_DELETED"))
                        .build())
        );


        auditServiceProps.productEventsTopic().addSubscription(new SqsSubscription(productEventsQueue,
                SqsSubscriptionProps.builder()
                        .filterPolicy(productsFilterPolicy)
                        .build()
                ));


        Queue productFailedEventsQueue = new Queue(this, "ProductFailedEventsQue",
                QueueProps.builder()
                        .queueName("product-failure-event")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(dlq).maxReceiveCount(2)
                                .build())
                        .build()
        );

        Map<String, SubscriptionFilter> productsFilterFailPolicy = new HashMap<>();
        productsFilterFailPolicy.put(
                "eventType", SubscriptionFilter.stringFilter(StringConditions.builder()
                        .allowlist(Arrays.asList("PRODUCT_FAILURE"))
                        .build())
        );

        auditServiceProps.productEventsTopic().addSubscription(new SqsSubscription(productFailedEventsQueue,
                SqsSubscriptionProps.builder()
                        .filterPolicy(productsFilterFailPolicy)
                        .build()
        ));

        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("audit-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));

        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup",
                        LogGroupProps.builder()
                                .logGroupName("AuditService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.FIVE_DAYS)
                                .build()))
                .streamPrefix("AuditService")
                .build());

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "9090");
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "auditservice");
        envVariables.put("AWS_EVENTS_DB", eventsDb.getTableName());
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_EVENTS_URL", productEventsQueue.getQueueUrl());
        envVariables.put("AWS_SQS_QUEUE_PRODUCT_FAILURE_EVENTS_URL", productFailedEventsQueue.getQueueUrl());
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");

        fargateTaskDefinition.addContainer("AuditServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(auditServiceProps.repository(), "4.0.1"))
                        .containerName("auditService")
                        .logging(logDriver)
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(9090)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                .containerName("XRayAuditService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayAuditService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.FIVE_DAYS)
                                .build()))
                        .streamPrefix("XRayAuditService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        productEventsQueue.grantConsumeMessages(fargateTaskDefinition.getTaskRole());
        productFailedEventsQueue.grantConsumeMessages(fargateTaskDefinition.getTaskRole());
        eventsDb.grantReadWriteData(fargateTaskDefinition.getTaskRole());

        ApplicationListener applicationListener = auditServiceProps.applicationLoadBalancer()
                .addListener("AuditServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(auditServiceProps.applicationLoadBalancer())
                        .build());

        FargateService fargateService = new FargateService(this, "AuditService",
                FargateServiceProps.builder()
                        .serviceName("AuditService")
                        .cluster(auditServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(1)
                        //DO NOT DO THIS IN PRODUCTION!!!
                        //.assignPublicIp(true)
                        .assignPublicIp(false)
                        .build());
        auditServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(
                Peer.ipv4(auditServiceProps.vpc().getVpcCidrBlock()), Port.tcp(9090));

        applicationListener.addTargets("AuditServiceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("auditServiceAlb")
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .port("9090")
                                .build())
                        .build()
        );

        NetworkListener networkListener = auditServiceProps.networkLoadBalancer()
                .addListener("AuditServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9090)
                        .protocol(
                                software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP
                        )
                        .build());

        networkListener.addTargets("AuditServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(9090)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("auditServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("auditService")
                                        .containerPort(9090)
                                        .protocol(Protocol.TCP)
                                        .build())
                        ))
                        .build()
        );

    }
}

record AuditServiceProps(
        Vpc vpc,
        Cluster cluster,
        NetworkLoadBalancer networkLoadBalancer,
        ApplicationLoadBalancer applicationLoadBalancer,
        Repository repository,
        Topic productEventsTopic
){}

