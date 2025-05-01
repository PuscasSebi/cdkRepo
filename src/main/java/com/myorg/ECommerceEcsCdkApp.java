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



        Map<String,String> infraTagsServiceAudit = new HashMap<>();
        infraTags.put("team", "Puscas");
        infraTags.put("cost", "AuditService");

        AuditServiceStack auditServiceStack = new AuditServiceStack(app, "AuditSService", StackProps.builder()
                .env(environment)
                .tags(infraTagsServiceAudit)
                .build(),
                new AuditServiceProps(vpcStack.getVps(), cluster.getCluster(), nlb.getNlb(), nlb.getAlb(),
                        ecr.getAuditServiceRepository(), productService.getProductEventsTopic()));
        auditServiceStack.addDependency(ecr);
        auditServiceStack.addDependency(nlb);
        auditServiceStack.addDependency(cluster);
        auditServiceStack.addDependency(vpcStack);


        ApiStack apiStack = new ApiStack(app, "Gateway", StackProps.builder()
                .env(environment)
                .tags(infraTags)
                .build(), new ApiStackProps(nlb.getVpcLink(), nlb.getNlb()));

        apiStack.addDependency(nlb);

        Map<String,String> infraTagsServiceInvoices = new HashMap<>();
        infraTags.put("team", "Puscas");
        infraTags.put("cost", "InvoiceService");

        InvoicesServiceStack invoiceServiceStack = new InvoicesServiceStack(app, "invoiceSService", StackProps.builder()
                .env(environment)
                .tags(infraTagsServiceInvoices)
                .build(),
                new InvoicesServiceProps(vpcStack.getVps(), cluster.getCluster(), nlb.getNlb(), nlb.getAlb(),
                        ecr.getInvoicesServiceRepository()));
        invoiceServiceStack.addDependency(ecr);
        invoiceServiceStack.addDependency(nlb);
        invoiceServiceStack.addDependency(cluster);
        invoiceServiceStack.addDependency(vpcStack);

        app.synth();
    }
}

