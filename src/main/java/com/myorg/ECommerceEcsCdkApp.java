package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import java.util.HashMap;
import java.util.Map;

public class ECommerceEcsCdkApp {
    public static void main(final String[] args) {
        App app = new App();
        Environment environment = Environment.builder()
                .account("451807216016")
                .region("eu-central-1")
                .build();

        Map<String,String> infraTags = new HashMap<>();
        infraTags.put("team", "Puscas");
        infraTags.put("cost", "ECommerceInfra");

        EcrStack ecr = new EcrStack(app, "Ecr", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build());


        VpcStack vpcStack = new VpcStack(app, "Vpc", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build());

        ClusterStackECS cluster = new ClusterStackECS(app, "Cluster", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(),
                new ClusterStackProps(vpcStack.getVps()));
        cluster.addDependency(vpcStack);

        NlbStack nlb = new NlbStack(app, "Nlb", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(), new NlbStackProps(vpcStack.getVps()));
        nlb.addDependency(vpcStack);



        Map<String,String> infraTagsService = new HashMap<>();
        infraTags.put("team", "Puscas");
        infraTags.put("cost", "ProductService");

        ProductServiceStack productService = new ProductServiceStack(app, "ProductService", StackProps.builder()
                .env(environment)
                .tags(infraTagsService)
                .build(),
                new ProductServiceStackProps(vpcStack.getVps(), cluster.getCluster(), nlb.getNlb(), nlb.getAlb(),
                        ecr.getProductServiceRepository()));
        productService.addDependency(ecr);
        productService.addDependency(nlb);
        productService.addDependency(cluster);
        productService.addDependency(vpcStack);

        app.synth();
    }
}

