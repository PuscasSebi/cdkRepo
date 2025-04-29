package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.constructs.Construct;


public class VpcStack extends Stack {

    private final Vpc vps;

    public VpcStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);
        vps = new Vpc(this, "Vpc",
        VpcProps.builder()
                .vpcName("ECommerceVpc")
                .maxAzs(2)
                .build()
        );
    }

    public Vpc getVps() {
        return vps;
    }


}
