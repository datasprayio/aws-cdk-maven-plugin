package io.dataspray.aws.cdk;


import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import software.amazon.awscdk.cloudassembly.schema.ContainerImageAssetMetadataEntry;
import software.amazon.awscdk.cloudassembly.schema.FileAssetMetadataEntry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

@Value
@Builder
public class StackDefinition {
    @Nonnull
    String stackName;
    @Nonnull
    Map<String, Object> template;
    @NonNull
    List<FileAssetMetadataEntry> fileAssets;
    @NonNull
    List<ContainerImageAssetMetadataEntry> imageAssets;
    @Nonnull
    String environment;
    @Nullable
    Integer requiredToolkitStackVersion;
    @Nonnull
    Map<String, ParameterDefinition> parameters;
    @Nonnull
    Map<String, String> parameterValues;
    @Nonnull
    Map<String, Map<String, Object>> resources;
    @Nonnull
    List<String> dependencies;
}
