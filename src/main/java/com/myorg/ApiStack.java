package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.constructs.Construct;

public class ApiStack extends Stack {

    public ApiStack(Construct scope, String id, StackProps props, ApiStackProps apiStackProps) {
        super(scope, id, props);

        RestApi restApi = new RestApi(this, "restApi",
                RestApiProps.builder()
                        .restApiName("ECommerceAPI")
                        .build());
        this. createProductResource(restApi, apiStackProps);
    }

    private void createProductResource(RestApi restApi, ApiStackProps apiStackProps){
        Resource products = restApi.getRoot().addResource("products");
        products.addMethod("GET", new Integration(
                IntegrationProps.builder()
                        .type(IntegrationType.HTTP_PROXY)
                        .integrationHttpMethod("GET")
                        .uri("http://"+ apiStackProps.networkLoadBalancer().getLoadBalancerDnsName()+":8080/api/products")
                        .options(IntegrationOptions.builder()
                                .vpcLink(apiStackProps.vpcLink())
                                .connectionType(ConnectionType.VPC_LINK)
                                .build())
                        .build())

        );
    }

}

record ApiStackProps(VpcLink vpcLink,
                     NetworkLoadBalancer networkLoadBalancer){}
