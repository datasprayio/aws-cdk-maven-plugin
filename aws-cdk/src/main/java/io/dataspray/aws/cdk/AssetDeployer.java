package io.dataspray.aws.cdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awscdk.cloudassembly.schema.*;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class AssetDeployer {

    private static final Logger logger = LoggerFactory.getLogger(AssetDeployer.class);

    public static final String ZIP_PACKAGING = "zip";
    public static final String FILE_PACKAGING = "file";
    public static final String IMAGE_PACKAGING = "container-image";

    private static final String BOOTSTRAP_VERSION_OUTPUT = "BootstrapVersion";
    private static final String BUCKET_NAME_OUTPUT = "BucketName";
    private static final String BUCKET_DOMAIN_NAME_OUTPUT = "BucketDomainName";
    private static final String ASSET_PREFIX_SEPARATOR = "||";

    private final Path cloudAssemblyDirectory;
    private final FileAssetPublisher fileAssetPublisher;
    private final DockerImageAssetPublisher dockerImagePublisher;
    private final EnvironmentResolver environmentResolver;

    public AssetDeployer(Path cloudAssemblyDirectory,
                         FileAssetPublisher fileAssetPublisher,
                         DockerImageAssetPublisher dockerImagePublisher,
                         EnvironmentResolver environmentResolver) {
        this.cloudAssemblyDirectory = cloudAssemblyDirectory;
        this.fileAssetPublisher = fileAssetPublisher;
        this.dockerImagePublisher = dockerImagePublisher;
        this.environmentResolver = environmentResolver;
    }

    /**
     * Deploy assets defined outside of stacks.
     *
     * @param imageAssets Image assets
     * @param fileAssets File assets
     */
    public void deploy(Map<String, DockerImageAsset> imageAssets, Map<String, FileAsset> fileAssets) {
        List<Runnable> publishmentTasks = new ArrayList<>();

        for (Map.Entry<String, DockerImageAsset> imageAssetEntry : imageAssets.entrySet()) {
            for (Map.Entry<String, DockerImageDestination> destinationEntry : imageAssetEntry.getValue().getDestinations().entrySet()) {
                ResolvedEnvironment environment = environmentResolver.resolveFromDestination(destinationEntry.getKey());
                publishmentTasks.add(createImagePublishmentTask(imageAssetEntry.getKey(), imageAssetEntry.getValue(), destinationEntry.getValue(), environment));
            }
        }

        for (Map.Entry<String, FileAsset> entry : fileAssets.entrySet()) {
            FileAsset fileAsset = entry.getValue();
            Objects.requireNonNull(fileAsset.getSource().getPath(),
                    "File asset has no path indicating an executable to be called to produce the asset which is not yet supported");
            for (Map.Entry<String, FileDestination> destinationEntry : fileAsset.getDestinations().entrySet()) {
                ResolvedEnvironment environment = environmentResolver.resolveFromDestination(destinationEntry.getKey());
                String bucketName = environment.resolveVariables(destinationEntry.getValue().getBucketName());
                String objectKey = destinationEntry.getValue().getObjectKey();

                publishmentTasks.add(() -> {
                    Path file = cloudAssemblyDirectory.resolve(fileAsset.getSource().getPath());
                    try {
                        fileAssetPublisher.publish(file, objectKey, bucketName, environment);
                    } catch (IOException e) {
                        throw StackDeploymentException.builder(environment)
                                .withCause("An error occurred while publishing the file asset " + file)
                                .withCause(e)
                                .build();
                    }
                });
            }
        }

        deploy(publishmentTasks);
    }

    /**
     * Deploy stack-specific assets returning required parameters for the stack
     *
     * @param imageAssets Image assets
     * @param fileAssets File assets
     * @return Parameter map with assets info required for stack deploy
     */
    /**
     *
     */
    public Map<String, ParameterValue> deploy(
            StackDefinition stack,
            Path cloudAssemblyDirectory,
            ResolvedEnvironment environment,
            ToolkitConfiguration toolkitConfiguration) {
        List<Runnable> publishmentTasks = Lists.newArrayList();
        Map<String, ParameterValue> assetParameters = Maps.newHashMap();

        Toolkit toolkit = null;
        for (FileAssetMetadataEntry asset : stack.getFileAssets()) {
            if (toolkit == null) {
                toolkit = getToolkit(stack, environment, toolkitConfiguration);
            }
            String bucketName = toolkit.getBucketName();
            String prefix = generatePrefix(asset);
            String filename = generateFilename(asset);
            assetParameters.put(asset.getS3BucketParameter(), ParameterValue.value(toolkit.getBucketName()));
            assetParameters.put(asset.getS3KeyParameter(), ParameterValue.value(String.join(ASSET_PREFIX_SEPARATOR, prefix, filename)));
            assetParameters.put(asset.getArtifactHashParameter(), ParameterValue.value(asset.getSourceHash()));

            publishmentTasks.add(() -> {
                Path file = cloudAssemblyDirectory.resolve(asset.getPath());
                try {
                    fileAssetPublisher.publish(file, prefix + filename, bucketName, environment);
                } catch (IOException e) {
                    throw StackDeploymentException.builder(stack.getStackName(), environment)
                            .withCause("An error occurred while publishing the file asset " + file)
                            .withCause(e)
                            .build();
                }
            });
        }

        for (ContainerImageAssetMetadataEntry asset : stack.getImageAssets()) {
            publishmentTasks.add(createImagePublishmentTask(asset.getId(), asset, environment));
        }

        deploy(publishmentTasks);

        return assetParameters;
    }

    private void deploy(List<Runnable> publishmentTasks) {
        try {
            publishmentTasks.forEach(Runnable::run);
        } catch (CdkException e) {
            throw StackDeploymentException.builder()
                    .withCause(e.getMessage())
                    .withCause(e.getCause())
                    .build();
        } catch (Exception e) {
            throw StackDeploymentException.builder()
                    .withCause(e)
                    .build();
        }
    }

    private Runnable createImagePublishmentTask(
            String assetId,
            DockerImageAsset imageAsset,
            DockerImageDestination destination,
            ResolvedEnvironment environment) {
        return createImagePublishmentTask(
                Optional.empty(),
                assetId,
                imageAsset.getSource().getDockerFile(),
                imageAsset.getSource().getDirectory(),
                imageAsset.getSource().getDockerBuildArgs(),
                imageAsset.getSource().getDockerBuildTarget(),
                destination.getRepositoryName(),
                destination.getImageTag(),
                environment);
    }

    private Runnable createImagePublishmentTask(
            String stackName,
            ContainerImageAssetMetadataEntry asset,
            ResolvedEnvironment environment) {
        return createImagePublishmentTask(
                Optional.of(stackName),
                asset.getId(),
                asset.getFile(),
                asset.getPath(),
                asset.getBuildArgs(),
                asset.getTarget(),
                asset.getRepositoryName(),
                asset.getImageTag(),
                environment);
    }

    private Runnable createImagePublishmentTask(
            Optional<String> stackNameOpt,
            String assetId,
            String dockerFile,
            String sourceDirectory,
            Map<String, String> dockerBuildArgs,
            String dockerBuildTarget,
            String repositoryName,
            String imageTag,
            ResolvedEnvironment environment) {
        Path contextDirectory = cloudAssemblyDirectory.resolve(sourceDirectory);
        if (!Files.exists(contextDirectory)) {
            throw StackDeploymentException.builder(stackNameOpt.orElse(null), environment)
                    .withCause("The Docker context directory doesn't exist: " + contextDirectory)
                    .build();
        }

        Path dockerfilePath;
        if (dockerFile != null) {
            dockerfilePath = contextDirectory.resolve(dockerFile);
            if (!Files.exists(dockerfilePath)) {
                throw StackDeploymentException.builder(stackNameOpt.orElse(null), environment)
                        .withCause("The Dockerfile doesn't exist: " + dockerfilePath)
                        .build();
            }
        } else {
            dockerfilePath = findDockerfile(contextDirectory)
                    .orElseThrow(() -> StackDeploymentException.builder(stackNameOpt.orElse(null), environment)
                            .withCause("Unable to find Dockerfile in the context directory " + contextDirectory)
                            .build());
        }

        return () -> {
            String localTag = String.join("-", "cdkasset", assetId.toLowerCase());
            ImageBuild imageBuild = ImageBuild.builder()
                    .withContextDirectory(contextDirectory)
                    .withDockerfile(dockerfilePath)
                    .withImageTag(localTag)
                    .withArguments(dockerBuildArgs)
                    .withTarget(dockerBuildTarget)
                    .build();
            String repositoryNameResolved = environment.resolveVariables(repositoryName);
            dockerImagePublisher.publish(repositoryNameResolved, imageTag, imageBuild, environment);
        };
    }

    private Optional<Path> findDockerfile(Path contextDirectory) {
        Path dockerfile = contextDirectory.resolve("Dockerfile");
        if (!Files.exists(dockerfile)) {
            dockerfile = contextDirectory.resolve("dockerfile");
        }

        return Optional.of(dockerfile).filter(Files::exists);
    }

    private String generateFilename(FileAssetMetadataEntry fileAsset) {
        StringBuilder fileName = new StringBuilder();
        fileName.append(fileAsset.getSourceHash());
        if (fileAsset.getPackaging().equals(ZIP_PACKAGING)) {
            fileName.append('.').append(ZIP_PACKAGING);
        } else {
            int extensionDelimiter = fileAsset.getPath().lastIndexOf('.');
            if (extensionDelimiter > 0) {
                fileName.append(fileAsset.getPath().substring(extensionDelimiter));
            }
        }

        return fileName.toString();
    }

    private String generatePrefix(FileAssetMetadataEntry fileAsset) {
        StringBuilder prefix = new StringBuilder();
        prefix.append("assets").append('/');
        if (!fileAsset.getId().equals(fileAsset.getSourceHash())) {
            prefix.append(fileAsset.getId()).append('/');
        }

        return prefix.toString();
    }

    private Toolkit getToolkit(
            StackDefinition stack,
            ResolvedEnvironment environment,
            ToolkitConfiguration toolkitConfiguration) {
        CloudFormationClient client = CloudFormationClientProvider.get(environment);
        Stack toolkitStack = Stacks.findStack(client, toolkitConfiguration.getStackName()).orElse(null);
        if (toolkitStack != null && Stacks.isInProgress(toolkitStack)) {
            logger.info("Waiting until toolkit stack reaches stable state, environment={}, stackName={}",
                    environment, toolkitConfiguration.getStackName());
            toolkitStack = awaitCompletion(toolkitStack, client);
        }

        if (toolkitStack == null || toolkitStack.stackStatus() == StackStatus.DELETE_COMPLETE ||
                toolkitStack.stackStatus() == StackStatus.ROLLBACK_COMPLETE) {
            throw StackDeploymentException.builder(stack.getStackName(), environment)
                    .withCause("The stack " + stack.getStackName() + " requires a bootstrap. Did you forged to " +
                            "add 'bootstrap' goal to the execution")
                    .build();
        }

        if (Stacks.isFailed(toolkitStack)) {
            throw StackDeploymentException.builder(stack.getStackName(), environment)
                    .withCause("The toolkit stack is in failed state. Please make sure that the toolkit stack is " +
                            "stable before the deployment")
                    .build();
        }

        Map<String, String> outputs = toolkitStack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));

        if (stack.getRequiredToolkitStackVersion() != null) {
            Integer toolkitStackVersion = Optional.ofNullable(outputs.get(BOOTSTRAP_VERSION_OUTPUT))
                    .map(Integer::parseInt)
                    .orElse(0);
            if (toolkitStackVersion < stack.getRequiredToolkitStackVersion()) {
                throw StackDeploymentException.builder(stack.getStackName(), environment)
                        .withCause("The toolkit stack version is lower than the minimum version required by the " +
                                "stack. Please update the toolkit stack or add 'bootstrap' goal to the plugin " +
                                "execution if you want the plugin to automatically create or update toolkit stack")
                        .build();
            }
        }

        String bucketName = outputs.get(BUCKET_NAME_OUTPUT);
        if (bucketName == null) {
            throw StackDeploymentException.builder(stack.getStackName(), environment)
                    .withCause("The toolkit stack " + toolkitConfiguration.getStackName() + " doesn't have a " +
                            "required output '" + BUCKET_NAME_OUTPUT + "'")
                    .build();
        }

        String bucketDomainName = outputs.get(BUCKET_DOMAIN_NAME_OUTPUT);
        if (bucketDomainName == null) {
            throw StackDeploymentException.builder(stack.getStackName(), environment)
                    .withCause("The toolkit stack " + toolkitConfiguration.getStackName() + " doesn't have a " +
                            "required output '" + BUCKET_DOMAIN_NAME_OUTPUT + "'")
                    .build();
        }

        return new Toolkit(bucketName, bucketDomainName);
    }

    private Stack awaitCompletion(Stack stack, CloudFormationClient client) {
        Stack completedStack;
        if (logger.isInfoEnabled()) {
            completedStack = Stacks.awaitCompletion(client, stack, new LoggingStackEventListener(Stacks.lastChange(stack)));
        } else {
            completedStack = Stacks.awaitCompletion(client, stack);
        }
        return completedStack;
    }
}
