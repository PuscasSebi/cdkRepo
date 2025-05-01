# Welcome to your CDK Java project!

This is a blank project for CDK development with Java.

The `cdk.json` file tells the CDK Toolkit how to execute your app.

It is a [Maven](https://maven.apache.org/) based project, so you can open this project with any Maven compatible Java IDE to build and run tests.

## Useful commands

 * `mvn package`     compile and run tests
 * `cdk ls`          list all stacks in the app
 * `cdk synth`       emits the synthesized CloudFormation template
 * `cdk deploy`      deploy this stack to your default AWS account/region
 * `cdk diff`        compare deployed stack with current state
 * `cdk docs`        open CDK documentation

Enjoy!


CDK project to support product/audit/invoice services infrastructure
everything is functional you just need npm cdk and aws keys

invoice.png shows invoice service
product_service.png shows product service

There's also a whole infrastructure png to show the big picture


please find all related repos here:
https://github.com/PuscasSebi/invoicesservice
https://github.com/PuscasSebi/auditservice
https://github.com/PuscasSebi/productservice
https://github.com/PuscasSebi/cdkRepo

build images and publish to ECR created in EcrStack

by default everything goes into eu-central-1 Frankfurt


cdk destroy Vpc Cluster Nlb ProductService AuditSService Gateway invoiceSService

do not destroy the Ecr if you ever plan on reusing the shit