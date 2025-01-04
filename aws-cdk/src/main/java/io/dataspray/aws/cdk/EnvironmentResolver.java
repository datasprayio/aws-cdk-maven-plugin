package io.dataspray.aws.cdk;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.PartitionMetadata;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsProfileRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProviderChain;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.sts.StsClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the execution environment i.e. populates account/region-agnostic environments with the default values and
 * lookups for the credentials to be used with the environment.
 *
 * The default region is determined using the default region provider chain. The default account is determined based
 * on the credentials provided by the default credentials provider chain.
 *
 * @see software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain
 * @see software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
 */
public class EnvironmentResolver {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentResolver.class);

    private static final Pattern ENVIRONMENT_URI_PATTERN = Pattern.compile("^(?<partition>aws(-.*)?)://(?<account>.*)/(?<region>.*)$");
    private static final String UNKNOWN_ACCOUNT = "unknown-account";
    private static final String CURRENT_ACCOUNT = "current_account";
    private static final String UNKNOWN_REGION = "unknown-region";
    private static final String CURRENT_REGION = "current_region";

    private final Region defaultRegion;
    private final String defaultAccount;
    private final AccountCredentialsProvider accountCredentialsProvider;
    private final Optional<URI> endpointUriOpt;

    private EnvironmentResolver(Region defaultRegion, @Nullable String defaultAccount, AccountCredentialsProvider accountCredentialsProvider, Optional<URI> endpointUriOpt) {
        this.defaultRegion = defaultRegion;
        this.defaultAccount = defaultAccount;
        this.accountCredentialsProvider = accountCredentialsProvider;
        this.endpointUriOpt = endpointUriOpt;
    }

    public static EnvironmentResolver create(@Nullable String profile, Optional<String> endpointUrlOpt) {
        Optional<URI> endpointUriOpt = endpointUrlOpt.map(URI::create);
        Region defaultRegion = fetchDefaultRegion(profile).orElse(Region.US_EAST_1); // us-east-1 is used by default in CDK
        AwsCredentials defaultCredentials = fetchDefaultCredentials(profile).orElse(null);
        String defaultAccount = defaultCredentials != null ? fetchAccount(defaultRegion, defaultCredentials, endpointUriOpt) : null;
        List<AccountCredentialsProvider> credentialsProviders = new ArrayList<>();
        if (defaultCredentials != null) {
            credentialsProviders.add(accountId -> {
                if (accountId.equals(defaultAccount)) {
                    return Optional.of(defaultCredentials);
                }

                return Optional.empty();
            });
        }

        AccountCredentialsProvider credentialsProvider = new AccountCredentialsProviderChain(credentialsProviders);
        return new EnvironmentResolver(defaultRegion, defaultAccount, credentialsProvider, endpointUriOpt);
    }

    /**
     * Resolves an environment from the given environment URI.
     *
     * @param environment an environment URI in the following format: {@code partition://account/region}
     * @return resolved environment
     * @throws IllegalArgumentException if the given environment URI is invalid
     * @throws CdkException in case the given environment is account-agnostic and a default account cannot be
     * determined or if credentials cannot be resolved for the account
     */
    public ResolvedEnvironment resolve(String environment) {
        logger.debug("Resolving env from {}", environment);

        Matcher matcher = ENVIRONMENT_URI_PATTERN.matcher(environment);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid environment format '" + environment + "'. Expected format: " +
                    "<partition>://<account>/<region>");
        }

        return resolveFromDestination(
                matcher.group("partition"),
                matcher.group("account"),
                matcher.group("region"));
    }

    public ResolvedEnvironment resolveFromDestination(String destinationKey) {
        logger.debug("Resolving env from destination {}", destinationKey);
        String[] parts = destinationKey.split("-", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid environment format '" + destinationKey + "'. Expected format: " +
                    "account-region");
        }

        return resolveFromDestination(
                null,
                parts[0],
                parts[1]);
    }

    private ResolvedEnvironment resolveFromDestination(@Nullable String partitionStr, @Nullable String accountStr, @Nullable String regionStr) {

        // Resolve account
        final String account;
        if (Strings.isNullOrEmpty(accountStr)
                || UNKNOWN_ACCOUNT.equals(accountStr)
                || CURRENT_ACCOUNT.equals(accountStr)) {
            account = defaultAccount;
        } else {
            account = accountStr;
        }
        if (account == null) {
            throw new CdkException("Unable to dynamically determine which AWS account to use for deployment");
        }

        // Resolve region
        final Region region;
        if (Strings.isNullOrEmpty(regionStr)
                || UNKNOWN_REGION.equals(regionStr)
                || CURRENT_REGION.equals(regionStr)) {
            region = defaultRegion;
        } else {
            region = Region.of(regionStr);
        }

        // Resolve partition
        final PartitionMetadata partition;
        if (Strings.isNullOrEmpty(partitionStr)) {
            partition = PartitionMetadata.of(region);
        } else {
            partition = PartitionMetadata.of(partitionStr);
        }

        // Resolve credentials
        AwsCredentials credentials = accountCredentialsProvider.get(account)
                .orElseThrow(() -> new CdkException("Credentials for the account '" + account +
                        "' are not available."));

        return new ResolvedEnvironment(partition, region, account, credentials, endpointUriOpt);
    }

    @Nonnull
    public Region getDefaultRegion() {
        return this.defaultRegion;
    }

    @Nullable
    public String getDefaultAccount() {
        return this.defaultAccount;
    }

    /**
     * Returns an account number for the given credentials.
     */
    private static String fetchAccount(Region region, AwsCredentials credentials, Optional<URI> endpointUriOpt) {
        StsClient stsClient = StsClient.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(endpointUriOpt.orElse(null))
                .build();
        return stsClient.getCallerIdentity().account();
    }

    private static Optional<Region> fetchDefaultRegion(@Nullable String profile) {
        List<AwsRegionProvider> providers = new ArrayList<>();
        if(!Strings.isNullOrEmpty(profile)) {
            providers.add(new AwsProfileRegionProvider(null, profile));
        }
        providers.add(new AwsProfileRegionProvider());
        providers.add(new DefaultAwsRegionProviderChain());
        AwsRegionProvider regionProvider = new AwsRegionProviderChain(providers.toArray(new AwsRegionProvider[0]));

        try {
            return Optional.of(regionProvider.getRegion());
        } catch (SdkClientException e) {
            return Optional.empty();
        }
    }

    private static Optional<AwsCredentials> fetchDefaultCredentials(@Nullable String profile) {
        AwsCredentialsProviderChain.Builder chainBuilder = AwsCredentialsProviderChain.builder();
        if(!Strings.isNullOrEmpty(profile)) {
            chainBuilder.addCredentialsProvider(ProfileCredentialsProvider.create(Strings.emptyToNull(profile)));
        }
        chainBuilder.addCredentialsProvider(DefaultCredentialsProvider.create());
        chainBuilder.addCredentialsProvider(ProfileCredentialsProvider.create());
        AwsCredentialsProviderChain credentialsProvider = chainBuilder.build();

        try {
            return Optional.of(credentialsProvider.resolveCredentials());
        } catch (Exception ignored) {
            // Although we should be fine catching SdkClientException | IllegalStateException
            // It's unclear whether there are additional exceptions that could be thrown
            return Optional.empty();
        }
    }
}
