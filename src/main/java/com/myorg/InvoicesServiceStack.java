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
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.s3.notifications.SqsDestination;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.amazon.awscdk.services.sqs.QueueProps;
import software.constructs.Construct;

import java.util.*;

public class InvoicesServiceStack  extends Stack {
    public InvoicesServiceStack(final Construct scope, final String id,
                                final StackProps props, InvoicesServiceProps invoicesServicePropsServiceProps) {
        super(scope, id, props);

        Table invoicesDb = new Table(this, "InvoideDb",
                TableProps.builder()
                        .tableName("invoices")
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
                        .build());

        Bucket bucket = new Bucket(this, "InvoicesBucket",
                BucketProps.builder()
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .autoDeleteObjects(true)
                        .lifecycleRules(Arrays.asList(LifecycleRule.builder()
                                        .enabled(true)
                                        .expiration(Duration.days(1))
                                .build()))
                        .build()
                );


        Queue invoiceEventsDlq = new Queue(this, "invoiveEventsDlq",
                QueueProps.builder()
                        .queueName("invoices-events-dlq")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .build());

        Queue invoiceEvents = new Queue(this, "InvoiceEvents",
                QueueProps.builder()
                        .queueName("invoices-events")
                        .enforceSsl(false)
                        .encryption(QueueEncryption.UNENCRYPTED)
                        .deadLetterQueue(DeadLetterQueue.builder()
                                .queue(invoiceEventsDlq)
                                .maxReceiveCount(3)
                                .build())
                        .build());

        bucket.addEventNotification(EventType.OBJECT_CREATED, new SqsDestination(invoiceEvents));


        FargateTaskDefinition fargateTaskDefinition = new FargateTaskDefinition(this, "TaskDefinition",
                FargateTaskDefinitionProps.builder()
                        .family("invoices-service")
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .build());
        fargateTaskDefinition.getTaskRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AWSXrayWriteOnlyAccess"));
        invoicesDb.grantReadWriteData(fargateTaskDefinition.getTaskRole());
        invoiceEvents.grantConsumeMessages(fargateTaskDefinition.getTaskRole());

        PolicyStatement invoicesBucket = new PolicyStatement(
          PolicyStatementProps.builder()
                  .effect(Effect.ALLOW)
                  .actions(Arrays.asList("s3:PutObject", "s3:DeleteObject", "s3:Getobject"))
                  .resources(Collections.singletonList(bucket.getBucketArn() + "/*"))
                  .build()
        );

        Policy s3TaskRolePolicy = new Policy(this, "s3Task polict", PolicyProps.builder()
                .statements(Collections.singletonList(invoicesBucket))
                .build());

        s3TaskRolePolicy.attachToRole(fargateTaskDefinition.getTaskRole());



        AwsLogDriver logDriver = new AwsLogDriver(AwsLogDriverProps.builder()
                .logGroup(new LogGroup(this, "LogGroup",
                        LogGroupProps.builder()
                                .logGroupName("InvoiceService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.FIVE_DAYS)
                                .build()))
                .streamPrefix("InvoiceService")
                .build());

        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("SERVER_PORT", "9095");
        envVariables.put("AWS_REGION", this.getRegion());
        envVariables.put("AWS_XRAY_DAEMON_ADDRESS", "0.0.0.0:2000");
        envVariables.put("AWS_XRAY_CONTEXT_MISSING", "IGNORE_ERROR");
        envVariables.put("AWS_XRAY_TRACING_NAME", "invoicesservice");
        envVariables.put("INVOICES_DB_NAME", invoicesDb.getTableName());
        envVariables.put("LOGGING_LEVEL_ROOT", "INFO");
        envVariables.put("INVOICES_BUCKET_NAME", bucket.getBucketName());
        envVariables.put("AWS_SQS_QUEUE_INVOICE_EVENTS_URL", invoiceEvents.getQueueUrl());

        fargateTaskDefinition.addContainer("InvoiceServiceContainer",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromEcrRepository(invoicesServicePropsServiceProps.repository(),
                                "1.0.1"))
                        .containerName("invoicesService")
                        .logging(logDriver)
                        .portMappings(Collections.singletonList(PortMapping.builder()
                                .containerPort(9095)
                                .protocol(Protocol.TCP)
                                .build()))
                        .environment(envVariables)
                        .cpu(384)
                        .memoryLimitMiB(896)
                        .build());

        fargateTaskDefinition.addContainer("xray", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("public.ecr.aws/xray/aws-xray-daemon:latest"))
                .containerName("XRayInvoiceService")
                .logging(new AwsLogDriver(AwsLogDriverProps.builder()
                        .logGroup(new LogGroup(this, "XRayLogGroup", LogGroupProps.builder()
                                .logGroupName("XRayInvoiceService")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.FIVE_DAYS)
                                .build()))
                        .streamPrefix("XRayInvoiceService")
                        .build()))
                .portMappings(Collections.singletonList(PortMapping.builder()
                        .containerPort(2000)
                        .protocol(Protocol.UDP)
                        .build()))
                .cpu(128)
                .memoryLimitMiB(128)
                .build());

        ApplicationListener applicationListener = invoicesServicePropsServiceProps.applicationLoadBalancer()
                .addListener("InvoiceServiceAlbListener", ApplicationListenerProps.builder()
                        .port(9095)
                        .protocol(ApplicationProtocol.HTTP)
                        .loadBalancer(invoicesServicePropsServiceProps.applicationLoadBalancer())
                        .build());

        FargateService fargateService = new FargateService(this, "InvoicesService",
                FargateServiceProps.builder()
                        .serviceName("InvoicesService")
                        .cluster(invoicesServicePropsServiceProps.cluster())
                        .taskDefinition(fargateTaskDefinition)
                        .desiredCount(1)
                        //DO NOT DO THIS IN PRODUCTION!!!
                        //.assignPublicIp(true)
                        .assignPublicIp(false)
                        .build());
        invoicesServicePropsServiceProps.repository().grantPull(Objects.requireNonNull(fargateTaskDefinition.getExecutionRole()));

        fargateService.getConnections().getSecurityGroups().get(0).addIngressRule(
                Peer.ipv4(invoicesServicePropsServiceProps.vpc().getVpcCidrBlock()), Port.tcp(9095));

        applicationListener.addTargets("InvoiceserviceAlbTarget",
                AddApplicationTargetsProps.builder()
                        .targetGroupName("InvoicesServiceAlb")
                        .port(9090)
                        .protocol(ApplicationProtocol.HTTP)
                        .targets(Collections.singletonList(fargateService))
                        .deregistrationDelay(Duration.seconds(30))
                        .healthCheck(HealthCheck.builder()
                                .enabled(true)
                                .interval(Duration.seconds(30))
                                .timeout(Duration.seconds(10))
                                .path("/actuator/health")
                                .port("9095")
                                .build())
                        .build()
        );

        NetworkListener networkListener = invoicesServicePropsServiceProps.networkLoadBalancer()
                .addListener("InvoicesServiceNlbListener", BaseNetworkListenerProps.builder()
                        .port(9095)
                        .protocol(
                                software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP
                        )
                        .build());

        networkListener.addTargets("InvoicesServiceNlbTarget",
                AddNetworkTargetsProps.builder()
                        .port(9095)
                        .protocol(software.amazon.awscdk.services.elasticloadbalancingv2.Protocol.TCP)
                        .targetGroupName("invoicesServiceNlb")
                        .targets(Collections.singletonList(
                                fargateService.loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                        .containerName("invoicesService")
                                        .containerPort(9095)
                                        .protocol(Protocol.TCP)
                                        .build())
                        ))
                        .build()
        );

    }

}

record InvoicesServiceProps(
            Vpc vpc,
            Cluster cluster,
            NetworkLoadBalancer networkLoadBalancer,
            ApplicationLoadBalancer applicationLoadBalancer,
            Repository repository
){}
