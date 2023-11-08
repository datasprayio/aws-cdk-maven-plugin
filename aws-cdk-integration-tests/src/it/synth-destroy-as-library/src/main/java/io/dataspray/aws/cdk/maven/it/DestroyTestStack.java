package io.dataspray.aws.cdk.maven.it;

import software.constructs.Construct;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;


public class DestroyTestStack extends Stack {

    public DestroyTestStack(final Construct scope, final String id) {
        super(scope, id);

        Table.Builder.create(this, "ItBusTable")
                .removalPolicy(RemovalPolicy.DESTROY)
                .tableName("it-bus")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .partitionKey(Attribute.builder()
                        .name("id")
                        .type(AttributeType.STRING)
                        .build())
                .build();
    }
}
