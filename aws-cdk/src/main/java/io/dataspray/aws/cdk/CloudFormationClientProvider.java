package io.dataspray.aws.cdk;

import com.google.common.collect.Maps;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

/**
 * Represents toolkit information for an execution environment.
 */
public class CloudFormationClientProvider {

    private static final ConcurrentMap<String, CloudFormationClient> clients = Maps.newConcurrentMap();

    public static CloudFormationClient get(ResolvedEnvironment environment) {
        return clients.computeIfAbsent(environment.getName(), name -> CloudFormationClient.builder()
                .region(environment.getRegion())
                .credentialsProvider(environment.getCredentialsProvider())
                .endpointOverride(environment.getEndpointUriOpt().orElse(null))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(200)
                        .connectionAcquisitionTimeout(Duration.ofSeconds(60))
                        .connectionMaxIdleTime(Duration.ofSeconds(60))
                        .socketTimeout(Duration.ofSeconds(60)))
                .build());
    }

    private CloudFormationClientProvider() {
        // Disallow ctor
    }
}
