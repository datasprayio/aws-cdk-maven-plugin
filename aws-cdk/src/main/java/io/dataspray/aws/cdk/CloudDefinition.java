package io.dataspray.aws.cdk;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awscdk.cloud_assembly_schema.AssemblyManifest;
import software.amazon.awscdk.cloudassembly.schema.*;
import software.amazon.awscdk.cxapi.CloudAssembly;
import software.amazon.jsii.JsiiObject;
import software.amazon.jsii.Kernel;
import software.amazon.jsii.NativeType;
import software.amazon.jsii.UnsafeCast;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CloudDefinition {

    private static final Logger logger = LoggerFactory.getLogger(CloudDefinition.class);

    private final Path cloudAssemblyDirectory;
    private final List<StackDefinition> stacks;
    private final Map<String, FileAsset> fileAssets;
    private final Map<String, DockerImageAsset> imageAssets;

    private CloudDefinition(Path cloudAssemblyDirectory, List<StackDefinition> stacks,
                            Map<String, FileAsset> fileAssets, Map<String, DockerImageAsset> imageAssets) {
        this.cloudAssemblyDirectory = cloudAssemblyDirectory;
        this.stacks = ImmutableList.copyOf(stacks);
        this.fileAssets = ImmutableMap.copyOf(fileAssets);
        this.imageAssets = ImmutableMap.copyOf(imageAssets);
    }

    /**
     * Returns the stacks defined in the cloud application. The stacks are sorted to correspond the deployment order,
     * i.e. the stack that should be deployed first will be first in the returned {@code List}.
     *
     * @return the stacks defined in the cloud application
     */
    @Nonnull
    public List<StackDefinition> getStacks() {
        return stacks;
    }

    @Nonnull
    public Map<String, FileAsset> getFileAssets() {
        return fileAssets;
    }

    @Nonnull
    public Map<String, DockerImageAsset> getImageAssets() {
        return imageAssets;
    }

    @Nonnull
    public Path getCloudAssemblyDirectory() {
        return cloudAssemblyDirectory;
    }

    @Override
    public String toString() {
        return "CloudDefinition{" +
                "stacks=" + stacks +
                "fileAssets=" + fileAssets +
                "imageAssets=" + imageAssets +
                '}';
    }

    public static CloudDefinition create(Path cloudAssemblyDirectory) {
        if (!Files.exists(cloudAssemblyDirectory)) {
            throw new CdkException("The cloud assembly directory " + cloudAssemblyDirectory + " doesn't exist. " +
                    "Did you forget to add 'synth' goal to the execution?");
        }
        return create(new CloudAssembly(cloudAssemblyDirectory.toString()));
    }

    public static CloudDefinition create(CloudAssembly cloudAssembly) {
        Path cloudAssemblyDirectory = Paths.get(cloudAssembly.getDirectory());
        AssemblyManifest assemblyManifest = cloudAssembly.getManifest();

        Map<String, FileAsset> fileAssets = Maps.newHashMap();
        Map<String, DockerImageAsset> imageAssets = Maps.newHashMap();
        if (assemblyManifest.getArtifacts() != null) {
            assemblyManifest.getArtifacts().values().stream()
                    .filter(artifactManifest -> ArtifactType.ASSET_MANIFEST.equals(artifactManifest.getType()))
                    .map(artifactManifest -> UnsafeCast.unsafeCast((JsiiObject) artifactManifest.getProperties(), AssetManifestProperties.class))
                    .map(AssetManifestProperties::getFile)
                    .map(assetManifestFile -> Manifest.loadAssetManifest(cloudAssemblyDirectory.resolve(assetManifestFile).toString()))
                    .forEach(assetManifest -> {
                        if (assetManifest.getFiles() != null) {
                            fileAssets.putAll(assetManifest.getFiles());
                        }
                        if (assetManifest.getDockerImages() != null) {
                            imageAssets.putAll(assetManifest.getDockerImages());
                        }
                    });
        }

        Map<String, StackDefinition> stacks = cloudAssembly.getStacks().stream()
                .map(stack -> {
                    String artifactId = stack.getId();
                    String stackName = ObjectUtils.firstNonNull(stack.getDisplayName(), artifactId);
                    Integer requiredToolkitStackVersion = Optional.ofNullable(stack.getRequiresBootstrapStackVersion())
                            .map(Number::intValue)
                            .orElse(null);
                    Map<String, Object> template = (Map<String, Object>) stack.getTemplate();
                    Map<String, Map<String, Object>> resources = (Map<String, Map<String, Object>>) template.getOrDefault("Resources", ImmutableMap.of());
                    Map<String, ParameterDefinition> parameters = getParameterDefinitions(template);
                    Map<String, String> parameterValues = stack.getParameters();

                    List<FileAssetMetadataEntry> stackFileAssets = Lists.newArrayList();
                    List<ContainerImageAssetMetadataEntry> stackImageAssets = Lists.newArrayList();
                    stack.findMetadataByType(MetadataTypes.ASSET).forEach(entry -> {
                        if (entry.getData() == null) {
                            return;
                        }
                        String packaging = Kernel.get(entry.getData(), "packaging", NativeType.forClass(String.class));
                        if (packaging == null) {
                            throw new CdkException("Manifest asset with missing packaging under data for path " + entry.getPath());
                        }
                        switch (packaging) {
                            case AssetDeployer.ZIP_PACKAGING:
                            case AssetDeployer.FILE_PACKAGING:
                                stackFileAssets.add(UnsafeCast.unsafeCast((JsiiObject) entry.getData(), FileAssetMetadataEntry.class));
                                break;
                            case AssetDeployer.IMAGE_PACKAGING:
                                stackImageAssets.add(UnsafeCast.unsafeCast((JsiiObject) entry.getData(), ContainerImageAssetMetadataEntry.class));
                                break;
                            default:
                                throw new CdkException("Unknown manifest asset packaging type " + packaging + " for path " + entry.getPath());
                        }
                    });

                    return StackDefinition.builder()
                            .stackName(stackName)
                            .template(template)
                            .fileAssets(stackFileAssets)
                            .imageAssets(stackImageAssets)
                            .environment("aws://" + stack.getEnvironment().getAccount() + "/" + stack.getEnvironment().getRegion())
                            .requiredToolkitStackVersion(requiredToolkitStackVersion)
                            .parameters(parameters)
                            .parameterValues(parameterValues)
                            .resources(resources)
                            .dependencies(stack.getManifest().getDependencies() != null ? stack.getManifest().getDependencies() : ImmutableList.of())
                            .build();
                })
                .collect(Collectors.toMap(StackDefinition::getStackName, Function.identity()));

        Set<String> visited = new HashSet<>();
        List<StackDefinition> sortedStacks = new ArrayList<>();
        stacks.keySet().forEach(stackName -> sortTopologically(stackName, stacks, visited, sortedStacks::add));
        return new CloudDefinition(cloudAssemblyDirectory, sortedStacks, fileAssets, imageAssets);
    }

    private static void sortTopologically(String stackName,
                                          Map<String, StackDefinition> stacks,
                                          Set<String> visited,
                                          Consumer<StackDefinition> consumer) {
        if (!visited.contains(stackName)) {
            visited.add(stackName);
            StackDefinition definition = stacks.get(stackName);
            if (definition != null) {
                for (String dependency : definition.getDependencies()) {
                    sortTopologically(dependency, stacks, visited, consumer);
                }
                consumer.accept(definition);
            }
        }
    }

    private static Map<String, ParameterDefinition> getParameterDefinitions(Map<String, Object> template) {
        Map<String, Map<String, Object>> parameters = (Map<String, Map<String, Object>>) template.getOrDefault("Parameters", Collections.emptyMap());

        return parameters.entrySet().stream()
                .map(parameter -> {
                    String name = parameter.getKey();
                    Object defaultValue = parameter.getValue().get("Default");
                    return new ParameterDefinition(name, defaultValue);
                })
                .collect(Collectors.toMap(ParameterDefinition::getName, Function.identity()));
    }
}
