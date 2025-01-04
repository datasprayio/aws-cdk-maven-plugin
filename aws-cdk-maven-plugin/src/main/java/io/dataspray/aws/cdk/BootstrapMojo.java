package io.dataspray.aws.cdk;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deploys toolkit stacks required by the CDK application.
 */
@Mojo(name = "bootstrap", defaultPhase = LifecyclePhase.DEPLOY)
public class BootstrapMojo extends AbstractCdkMojo {

    /**
     * The name of the CDK toolkit stack.
     */
    @Parameter(property = "aws.cdk.toolkit.stack.name", defaultValue = AwsCdk.DEFAULT_TOOLKIT_STACK_NAME)
    private String toolkitStackName;

    /**
     * Input parameters for the bootstrap stack. In the case of an update, existing values will be reused.
     */
    @Parameter
    private Map<String, String> bootstrapParameters;

    /**
     * Tags that will be added to the bootstrap stack.
     */
    @Parameter
    private Map<String, String> bootstrapTags;

    /**
     * Stacks, for which bootstrapping will be performed if it's required.
     */
    @Parameter(property = "aws.cdk.stacks")
    private Set<String> stacks;

    @Override
    public void execute(Path cloudAssemblyDirectory, Optional<String> profileOpt, Optional<String> endpointUrlOpt) {
        AwsCdk.bootstrap().execute(cloudAssemblyDirectory, toolkitStackName, stacks, bootstrapParameters, bootstrapTags, profileOpt, endpointUrlOpt);
    }
}
