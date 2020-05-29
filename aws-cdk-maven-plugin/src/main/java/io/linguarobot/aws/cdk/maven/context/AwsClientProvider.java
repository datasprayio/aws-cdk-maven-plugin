package io.linguarobot.aws.cdk.maven.context;

import software.amazon.awscdk.cxapi.Environment;
import software.amazon.awssdk.core.SdkClient;

public interface AwsClientProvider {

    <T extends SdkClient> T getClient(Class<T> clientType, Environment environment);

}
