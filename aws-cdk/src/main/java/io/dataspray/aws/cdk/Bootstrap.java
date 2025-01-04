package io.dataspray.aws.cdk;

import software.amazon.awscdk.cxapi.CloudAssembly;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deploys toolkit stacks required by the CDK application.
 */
public interface Bootstrap {

    /**
     * Bootstrap CDK
     *
     * @param cloudAssemblyDirectory Directory of synthesized stack(s)
     * @param toolkitStackName The name of the CDK toolkit stack.
     * @param stacks Stacks, for which bootstrapping will be performed if it's required.
     * @param bootstrapParameters Input parameters for the bootstrap stack. In the case of an update, existing values
     * will be reused.
     * @param bootstrapTags Tags that will be added to the bootstrap stack.
     * @param profileOpt Optional AWS account profile name
     * @param endpointUrlOpt Optional endpoint URL for testing when mocking out AWS services
     */
    void execute(
            Path cloudAssemblyDirectory,
            String toolkitStackName,
            Set<String> stacks,
            Map<String, String> bootstrapParameters,
            Map<String, String> bootstrapTags,
            Optional<String> profileOpt,
            Optional<String> endpointUrlOpt);

    /**
     * Bootstrap CDK for all stacks
     *
     * @param cloudAssembly Cloud assembly created via app.synth()
     */
    void execute(CloudAssembly cloudAssembly);

    /**
     * Bootstrap CDK
     *
     * @param cloudAssembly Cloud assembly created via app.synth()
     * @param stacks Stacks, for which bootstrapping will be performed if it's required.
     */
    void execute(
            CloudAssembly cloudAssembly,
            String... stacks);

    /**
     * Bootstrap CDK
     *
     * @param cloudAssembly Cloud assembly created via app.synth()
     * @param stacks Stacks, for which bootstrapping will be performed if it's required.
     * @param profile AWS account profile name
     */
    void execute(
            CloudAssembly cloudAssembly,
            Set<String> stacks,
            String profile);

    /**
     * Bootstrap CDK
     *
     * @param cloudAssembly Cloud assembly created via app.synth()
     * @param toolkitStackName The name of the CDK toolkit stack.
     * @param stacks Stacks, for which bootstrapping will be performed if it's required.
     * @param bootstrapParameters Input parameters for the bootstrap stack. In the case of an update, existing values
     * will be reused.
     * @param bootstrapTags Tags that will be added to the bootstrap stack.
     * @param profileOpt Optional AWS account profile name
     * @param endpointUrlOpt Optional endpoint URL for testing when mocking out AWS services
     */
    void execute(
            CloudAssembly cloudAssembly,
            String toolkitStackName,
            Set<String> stacks,
            Map<String, String> bootstrapParameters,
            Map<String, String> bootstrapTags,
            Optional<String> profileOpt,
            Optional<String> endpointUrlOpt);
}
