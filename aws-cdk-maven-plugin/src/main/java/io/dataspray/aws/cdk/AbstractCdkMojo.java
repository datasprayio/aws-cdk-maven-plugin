package io.dataspray.aws.cdk;

import com.google.common.base.Strings;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * An abstract Mojo that defines some parameters common for synthesis and deployment.
 */
public abstract class AbstractCdkMojo extends AbstractMojo {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCdkMojo.class);

    @Parameter(defaultValue = "${settings}", required = true, readonly = true)
    private Settings settings;

    /**
     * A profile that will be used while looking for credentials and region.
     */
    @Parameter(property = "aws.cdk.profile")
    private String profile;

    /**
     * A profile that will be used while looking for credentials and region.
     */
    @Parameter(property = "aws.endpointUrl")
    private String endpointUrl;

    /**
     * A cloud assembly directory.
     */
    @Parameter(property = "aws.cdk.cloud.assembly.directory", defaultValue = "${project.build.directory}/cdk.out")
    private File cloudAssemblyDirectory;

    /**
     * Enables/disables an execution.
     */
    @Parameter(defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (!skip) {
            try {
                execute(cloudAssemblyDirectory.toPath(),
                        Optional.ofNullable(Strings.emptyToNull(profile)),
                        Optional.ofNullable(Strings.emptyToNull(endpointUrl)));
            } catch (Exception e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        } else {
            logger.debug("The execution is configured to be skipped");
        }
    }

    public abstract void execute(
            Path cloudAssemblyDirectory,
            Optional<String> profileOpt,
            Optional<String> endpointUrlOpt);
}
