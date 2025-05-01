package com.myorg;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class ApiStack extends Stack {

    public ApiStack(Construct scope, String id, StackProps props, ApiStackProps apiStackProps) {
        super(scope, id, props);

        LogGroup logGroup = new LogGroup(this, "EcommersAPiLogs",
                LogGroupProps.builder()
                        .logGroupName("ECommersApi")
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .retention(RetentionDays.FIVE_DAYS)
                        .build());

        RestApi restApi = new RestApi(this, "restApi",
                RestApiProps.builder()
                        .restApiName("ECommerceAPI")
                        .cloudWatchRole(true)
                        .deployOptions(StageOptions.builder()
                                .loggingLevel(MethodLoggingLevel.INFO)
                                .accessLogDestination(new LogGroupLogDestination(logGroup))
                                .accessLogFormat(AccessLogFormat.jsonWithStandardFields(
                                        JsonWithStandardFieldProps.builder()
                                                .caller(true)
                                                .httpMethod(true)
                                                .ip(true)
                                                .protocol(true)
                                                .requestTime(true)
                                                .resourcePath(true)
                                                .responseLength(true)
                                                .status(true)
                                                .user(true)
                                                .build()
                                ))
                                .build())
                        .build());
        Resource productResource = this.createProductResource(restApi, apiStackProps);
        createdProductEventsResource(restApi, apiStackProps, productResource);

        createInvoicesResource(restApi, apiStackProps);
    }

    private void createdProductEventsResource(RestApi restApi,
                                              ApiStackProps apiStackProps,
                                              Resource productResource) {
        //product/resource
        Resource productEventsResource = productResource.addResource("events");

        Map<String, String> productsEventsIntegrationParams = new HashMap<>();
        productsEventsIntegrationParams.put("integration.request.header.requestId", "context.requestId");


        Map<String, Boolean> productEventsMethodParams = new HashMap<>();
        productEventsMethodParams.put("method.request.header.requestId", false);
        productEventsMethodParams.put("method.request.querystring.eventType", false);
        productEventsMethodParams.put("method.request.querystring.limit", false);
        productEventsMethodParams.put("method.request.querystring.from", false);
        productEventsMethodParams.put("method.request.querystring.to", false);
        productEventsMethodParams.put("method.request.querystring.exclusiveStartTimestamp", false);

        //GET /products/events?eventType=PRODUCT_CREATED&limit=10&from=5&to=15&exclusiveStartTimestamp=123
        productEventsResource.addMethod("GET", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("GET")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":9090/api/products/events")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productsEventsIntegrationParams)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestValidator(new RequestValidator(this, "ProductEventsValidator",
                                RequestValidatorProps.builder()
                                        .restApi(restApi)
                                        .requestValidatorName("productEventsValidator")
                                        .validateRequestParameters(true)
                                        .build()
                        ))
                        .requestParameters(productEventsMethodParams)
                        .build()

        );

    }

    private void createInvoicesResource(RestApi restApi, ApiStackProps apiStackProps) {
        Resource invoicesResource = restApi.getRoot().addResource("invoices");
        Map<String, String> invoicesIntegrationParams = new HashMap<>();
        invoicesIntegrationParams.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> invoicesMethodParams = new HashMap<>();
        invoicesMethodParams.put("method.request.header.requestId", false);

        //Post /invoices - create presign url
        invoicesResource.addMethod("POST", new Integration(IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("POST")
                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                        ":9095/api/invoices")
                .options(IntegrationOptions.builder()
                        .vpcLink(apiStackProps.vpcLink())
                        .connectionType(ConnectionType.VPC_LINK)
                        .requestParameters(invoicesIntegrationParams)
                        .build())
                .build()), MethodOptions.builder()
                .requestValidator(new RequestValidator(this, "InvoiceValidator2",
                        RequestValidatorProps.builder()
                                .restApi(restApi)
                                .requestValidatorName("InvoicesValidator2")
                                .validateRequestParameters(true)
                                .build()
                ))
                .requestParameters(invoicesMethodParams)
                .build());

        //GET /api/invoices/transactions/{fileTransactionId}
        Map<String, String> invoicesFileIntegrationParams = new HashMap<>();
        invoicesFileIntegrationParams.put("integration.request.header.requestId", "context.requestId");
        invoicesFileIntegrationParams.put("integration.request.path.fileTransactionId", "method.request.path.fileTransactionId");

        Map<String, Boolean> invoicesFileMethodParams = new HashMap<>();
        invoicesFileMethodParams.put("method.request.header.requestId", false);
        invoicesFileMethodParams.put("method.request.path.fileTransactionId", true);


        Resource invoiceTransactionsResource = invoicesResource.addResource("transactions");
        Resource fileTransactionId = invoiceTransactionsResource.addResource("{fileTransactionId}");

        fileTransactionId.addMethod("GET", new Integration(IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":9095/api/invoices/transactions/{fileTransactionId}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(invoicesFileIntegrationParams)
                                .build())
                        .build()),
                MethodOptions.builder()
                        .requestValidator(new RequestValidator(this, "InvoiceTransactionValidator",
                                RequestValidatorProps.builder()
                                        .restApi(restApi)
                                        .requestValidatorName("InvoiceTransactionValidator")
                                        .validateRequestParameters(true)
                                        .build()
                        ))
                        .requestParameters(invoicesFileMethodParams)
                        .build()
        );

        //GEt /invoices?email=puscas@gmail.com

        Map<String, Boolean> invoicesEmailMethodParams = new HashMap<>();
        invoicesEmailMethodParams.put("method.request.header.requestId", false);
        invoicesEmailMethodParams.put("method.request.querystring.email", false);

        //Post /invoices - create presign url
        invoicesResource.addMethod("GET", new Integration(IntegrationProps.builder()
                .type(IntegrationType.HTTP_PROXY)
                .integrationHttpMethod("GET")
                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                        ":9095/api/invoices")
                .options(IntegrationOptions.builder()
                        .vpcLink(apiStackProps.vpcLink())
                        .connectionType(ConnectionType.VPC_LINK)
                        .requestParameters(invoicesIntegrationParams)
                        .build())
                .build()), MethodOptions.builder()
                .requestValidator(new RequestValidator(this, "EmailInvoiceValidatorEmail",
                        RequestValidatorProps.builder()
                                .restApi(restApi)
                                .requestValidatorName("EmailInvoiceValidatorEmail")
                                .validateRequestParameters(true)
                                .build()

                ))
                .requestParameters(invoicesEmailMethodParams)
                .build());


    }

    private Resource createProductResource(RestApi restApi, ApiStackProps apiStackProps) {
        Map<String, String> productsIntegrationParams = new HashMap<>();
        productsIntegrationParams.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productsMethodParams = new HashMap<>();
        productsMethodParams.put("method.request.header.requestId", false);
        productsMethodParams.put("method.request.querystring.code", false);

        //products
        Resource productsResource = restApi.getRoot().addResource("products");

        //GET /products
        productsResource.addMethod("GET", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("GET")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() + ":8080/api/products")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .requestParameters(productsIntegrationParams)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productsMethodParams)
                        .build()

        );
        RequestValidator productRequestValidator = new RequestValidator(this, "ProductRequestValidator",
                RequestValidatorProps.builder()
                        .restApi(restApi)
                        .requestValidatorName("Product request validator")
                        .validateRequestBody(true)
                        .build()
        );

        Map<String, JsonSchema> productModelProperties = new HashMap<>();
        productModelProperties.put("name", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(50)
                .build());
        productModelProperties.put("code", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(15)
                .build());
        productModelProperties.put("model", JsonSchema.builder()
                .type(JsonSchemaType.STRING)
                .minLength(5)
                .maxLength(50)
                .build());
        productModelProperties.put("price", JsonSchema.builder()
                .type(JsonSchemaType.NUMBER)
                .minimum(10.0)
                .maximum(1000.0)
                .build());

        Model productModel = new Model(this, "ProductModel",
                ModelProps.builder()
                        .modelName("ProductModel")
                        .restApi(restApi)
                        .contentType("application/json")
                        .schema(JsonSchema.builder()
                                .type(JsonSchemaType.OBJECT)
                                .properties(productModelProperties)
                                .required(Arrays.asList("name", "code"))
                                .build())
                        .build()
        );

        Map<String, Model> productRequestModels = new HashMap<>();
        productRequestModels.put("application/json", productModel);

        // POST /products
        productsResource.addMethod("POST", new Integration(
                        IntegrationProps.builder()
                                .type(IntegrationType.HTTP_PROXY)
                                .integrationHttpMethod("POST")
                                .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                        ":8080/api/products")
                                .options(IntegrationOptions.builder()
                                        .vpcLink(apiStackProps.vpcLink())
                                        .requestParameters(productsIntegrationParams)
                                        .connectionType(ConnectionType.VPC_LINK)
                                        .build())
                                .build()),
                MethodOptions.builder()
                        .requestParameters(productsMethodParams)
                        .requestValidator(productRequestValidator)
                        .requestModels(productRequestModels)
                        .build()

        );

        // PUT /products/{id}
        Map<String, String> productIdIntegrationParameters = new HashMap<>();
        productIdIntegrationParameters.put("integration.request.path.id", "method.request.path.id");
        productsIntegrationParams.put("integration.request.header.requestId", "context.requestId");

        Map<String, Boolean> productIdMethodParameters = new HashMap<>();
        productIdMethodParameters.put("method.request.path.id", true);
        productsMethodParams.put("method.request.header.requestId", false);


        Resource productIdResource = productsResource.addResource("{id}");
        productIdResource.addMethod("PUT", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("PUT")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters)
                .build());

        // GET /products/{id}
        productIdResource.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters)
                .build());

        // DELETE /products/{id}
        productIdResource.addMethod("DELETE", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("DELETE")
                        .uri("http://" + apiStackProps.networkLoadBalancer().getLoadBalancerDnsName() +
                                ":8080/api/products/{id}")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .requestParameters(productIdIntegrationParameters)
                                .build())
                        .build()), MethodOptions.builder()
                .requestParameters(productIdMethodParameters)
                .build());

        return productsResource;
    }

}

record ApiStackProps(VpcLink vpcLink,
                     NetworkLoadBalancer networkLoadBalancer) {
}
