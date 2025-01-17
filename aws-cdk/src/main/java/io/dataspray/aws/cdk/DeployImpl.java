package io.dataspray.aws.cdk;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.dataspray.aws.cdk.process.DefaultProcessRunner;
import io.dataspray.aws.cdk.process.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awscdk.cxapi.CloudAssembly;
import software.amazon.awscdk.cxapi.CloudFormationStackArtifact;

import java.nio.file.Path;
import java.util.*;

/**
 * Deploys the synthesized templates to the AWS.
 */
public class DeployImpl implements Deploy {

    private static final Logger logger = LoggerFactory.getLogger(DeployImpl.class);

    @Override
    public void execute(CloudAssembly cloudAssembly, String toolkitStackName, Set<String> stacks, Map<String, String> parameters, Map<String, String> tags, Set<String> notificationArns, Optional<String> profileOpt, Optional<String> endpointUrlOpt) {
        execute(CloudDefinition.create(cloudAssembly), toolkitStackName, stacks, parameters, tags, notificationArns, profileOpt, endpointUrlOpt);
    }

    @Override
    public void execute(Path cloudAssemblyDirectory, String toolkitStackName, Set<String> stacks, Map<String, String> parameters, Map<String, String> tags, Set<String> notificationArns, Optional<String> profileOpt, Optional<String> endpointUrlOpt) {
        execute(CloudDefinition.create(cloudAssemblyDirectory), toolkitStackName, stacks, parameters, tags, notificationArns, profileOpt, endpointUrlOpt);
    }

    @Override
    public void execute(CloudAssembly cloudAssembly) {
        execute(cloudAssembly, AwsCdk.DEFAULT_TOOLKIT_STACK_NAME,
                ImmutableSet.copyOf(Lists.transform(cloudAssembly.getStacks(), CloudFormationStackArtifact::getStackName)),
                null, null, ImmutableSet.of(), Optional.empty(), Optional.empty());
    }

    @Override
    public void execute(CloudAssembly cloudAssembly, String... stacks) {
        execute(cloudAssembly, AwsCdk.DEFAULT_TOOLKIT_STACK_NAME, ImmutableSet.copyOf(stacks), null, null, ImmutableSet.of(), Optional.empty(), Optional.empty());
    }

    @Override
    public void execute(CloudAssembly cloudAssembly, Set<String> stacks, String profile) {
        execute(cloudAssembly, AwsCdk.DEFAULT_TOOLKIT_STACK_NAME, stacks, null, null, ImmutableSet.of(), Optional.of(profile), Optional.empty());
    }

    private void execute(CloudDefinition cloudDefinition, String toolkitStackName, Set<String> stacks, Map<String, String> parameters, Map<String, String> tags, Set<String> notificationArns, Optional<String> profileOpt, Optional<String> endpointUrlOpt) {
        EnvironmentResolver environmentResolver = EnvironmentResolver.create(profileOpt.orElse(null), endpointUrlOpt);
        if (stacks != null && !stacks.isEmpty() && logger.isWarnEnabled()) {
            Set<String> undefinedStacks = new HashSet<>(stacks);
            cloudDefinition.getStacks().forEach(stack -> undefinedStacks.remove(stack.getStackName()));
            if (!undefinedStacks.isEmpty()) {
                logger.warn("The following stacks are not defined in the cloud application and can not be deployed: {}",
                        String.join(", ", undefinedStacks));
            }
        }

        ProcessRunner processRunner = new DefaultProcessRunner(cloudDefinition.getCloudAssemblyDirectory().toFile());
        FileAssetPublisher filePublisher = new FileAssetPublisher();
        DockerImageAssetPublisher dockerImagePublisher = new DockerImageAssetPublisher(processRunner);
        AssetDeployer assetDeployer = new AssetDeployer(
                cloudDefinition.getCloudAssemblyDirectory(),
                new FileAssetPublisher(),
                new DockerImageAssetPublisher(processRunner),
                environmentResolver);
        assetDeployer.deploy(cloudDefinition.getImageAssets(), cloudDefinition.getFileAssets());

        Map<String, StackDeployer> deployers = new HashMap<>();
        for (StackDefinition stack : cloudDefinition.getStacks()) {
            if (stacks == null || stacks.isEmpty() || stacks.contains(stack.getStackName())) {
                StackDeployer deployer = deployers.computeIfAbsent(stack.getEnvironment(), environment -> {
                    ResolvedEnvironment resolvedEnvironment = environmentResolver.resolve(environment);
                    ToolkitConfiguration toolkitConfiguration = new ToolkitConfiguration(toolkitStackName);
                    return new StackDeployer(cloudDefinition.getCloudAssemblyDirectory(), resolvedEnvironment,
                            toolkitConfiguration, filePublisher, dockerImagePublisher, notificationArns);
                });

                Map<String, ParameterValue> assetParameters = assetDeployer.deploy(
                        stack,
                        cloudDefinition.getCloudAssemblyDirectory(),
                        deployer.getEnvironment(),
                        deployer.getToolkitConfiguration());

                if (!stack.getResources().isEmpty()) {
                    deployer.deploy(stack, assetParameters, parameters != null ? parameters : ImmutableMap.of(), tags != null ? tags : ImmutableMap.of());
                } else {
                    deployer.destroy(stack);
                }
            }
        }
    }
}
