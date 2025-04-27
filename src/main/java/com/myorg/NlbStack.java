package com.myorg;

import lombok.Getter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.VpcLink;
import software.amazon.awscdk.services.apigateway.VpcLinkProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.NetworkLoadBalancerProps;
import software.constructs.Construct;

import java.util.Collections;


public class NlbStack extends Stack {
    @Getter
    private final VpcLink vpcLink;
    @Getter
    private final NetworkLoadBalancer nlb;
    @Getter
    private final ApplicationLoadBalancer alb;
    public NlbStack(Construct scope, String id, StackProps props, NlbStackProps nlbStackProps) {
        super(scope, id, props);

        nlb = new NetworkLoadBalancer(this, "Nlb", NetworkLoadBalancerProps.builder()
                .loadBalancerName("ECommerceNlb")
                .internetFacing(false)
                .vpc(nlbStackProps.vpc())
                .build());

        this.vpcLink = new VpcLink(this, "VpcLink", VpcLinkProps.builder()
                .vpcLinkName("ECommerceVpcLink")
                .targets(Collections.singletonList(nlb))
                .build());

        this.alb = new ApplicationLoadBalancer(this, "Alb", ApplicationLoadBalancerProps.builder()
                .loadBalancerName("ECommerceAlb")
                .internetFacing(false)
                .vpc(nlbStackProps.vpc())
                .build());
    }
}

record NlbStackProps(Vpc vpc){}
