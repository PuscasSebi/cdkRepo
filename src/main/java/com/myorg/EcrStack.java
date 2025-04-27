package com.myorg;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryProps;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.constructs.Construct;

public class EcrStack extends Stack {


    private final Repository productServiceRepository;

    public EcrStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);
        productServiceRepository =
                new Repository(this, "ProductsService", RepositoryProps.builder()
                .repositoryName("productsservice")
                .removalPolicy(RemovalPolicy.DESTROY)
                .imageTagMutability(TagMutability.IMMUTABLE)
                .emptyOnDelete(true)
                .build());
    }

    public Repository getProductServiceRepository() {
        return productServiceRepository;
    }

}
