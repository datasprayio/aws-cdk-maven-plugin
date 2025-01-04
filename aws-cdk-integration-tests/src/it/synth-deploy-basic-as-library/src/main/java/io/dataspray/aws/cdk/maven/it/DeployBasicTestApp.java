package io.dataspray.aws.cdk.maven.it;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.dataspray.aws.cdk.AwsCdk;
import software.amazon.awscdk.App;
import software.amazon.awscdk.cxapi.CloudAssembly;
import software.amazon.awscdk.cxapi.CloudFormationStackArtifact;

import java.util.Optional;


public class DeployBasicTestApp {

    public static void main(String[] args) {
        String stage = args.length > 0 ? args[0] : "dev";
        App app = new App();
        new DeployBasicTestStack(app, "synth-deploy-basic-test-stack-as-library", stage);
        CloudAssembly assembly = app.synth();
        AwsCdk.bootstrap().execute(assembly);
        AwsCdk.deploy().execute(
                assembly,
                "basic-it-cdk-toolkit-as-library",
                ImmutableSet.copyOf(Lists.transform(assembly.getStacks(), CloudFormationStackArtifact::getStackName)),
                ImmutableMap.of("Parameter", "OverriddenValue"),
                ImmutableMap.of("testTag", "testTagValue"),
                ImmutableSet.of(),
                Optional.empty(),
                Optional.empty());
    }

}
