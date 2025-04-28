package com.myorg;

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
        this.createProductResource(restApi, apiStackProps);


    }

    private void createProductResource(RestApi restApi, ApiStackProps apiStackProps){
        Map<String,String> productsIntegrationParams = new HashMap<>();
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
                        .uri("http://"+ apiStackProps.networkLoadBalancer().getLoadBalancerDnsName()+":8080/api/products")
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

    }

}

record ApiStackProps(VpcLink vpcLink,
                     NetworkLoadBalancer networkLoadBalancer){}
