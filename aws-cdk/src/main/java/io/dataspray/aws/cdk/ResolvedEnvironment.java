package io.dataspray.aws.cdk;

import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.PartitionMetadata;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.util.Optional;

/**
 * Represents a resolved execution environment.
 */
@Getter
public class ResolvedEnvironment {

    private final String name;
    private final PartitionMetadata partition;
    private final Region region;
    private final String account;
    private final AwsCredentialsProvider credentialsProvider;
    private final Optional<URI> endpointUriOpt;

    public ResolvedEnvironment(PartitionMetadata partition, Region region, String account, AwsCredentials credentials, Optional<URI> endpointUriOpt) {
        this.name = partition.id() + "://" + account + "/" + region;
        this.partition = partition;
        this.region = region;
        this.account = account;
        this.credentialsProvider = StaticCredentialsProvider.create(credentials);
        this.endpointUriOpt = endpointUriOpt;
    }

    public AwsCredentials getCredentials() {
        return credentialsProvider.resolveCredentials();
    }

    public String resolveVariables(String input) {
        return input.replaceAll("\\$\\{AWS::Region}", region.id())
                .replaceAll("\\$\\{AWS::AccountId}", account)
                .replaceAll("\\$\\{AWS::Partition}", partition.id());
    }

    @Override
    public String toString() {
        return getName();
    }

}
