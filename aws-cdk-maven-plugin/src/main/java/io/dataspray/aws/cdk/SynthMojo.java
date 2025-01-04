package io.dataspray.aws.cdk;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.dataspray.aws.cdk.context.*;
import io.dataspray.aws.cdk.node.*;
import io.dataspray.aws.cdk.process.DefaultProcessRunner;
import io.dataspray.aws.cdk.process.ProcessContext;
import io.dataspray.aws.cdk.process.ProcessExecutionException;
import io.dataspray.aws.cdk.process.ProcessRunner;
import io.dataspray.aws.cdk.runtime.Synthesizer;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.ContextEnabled;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awscdk.cloud_assembly_schema.AssemblyManifest;
import software.amazon.awscdk.cloud_assembly_schema.ContextProvider;
import software.amazon.awscdk.cloud_assembly_schema.Manifest;
import software.amazon.awscdk.cloud_assembly_schema.MissingContext;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.jsii.JsiiObject;
import software.amazon.jsii.UnsafeCast;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Synthesizes CloudFormation templates for a CDK application.
 */
@Mojo(
        name = "synth",
        instantiationStrategy = InstantiationStrategy.PER_LOOKUP,
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class SynthMojo extends AbstractCdkMojo implements ContextEnabled {

    private static final Logger logger = LoggerFactory.getLogger(DeployMojo.class);

    private static final String CDK_CONTEXT_FILE_NAME = "cdk.context.json";
    private static final NodeVersion MINIMUM_REQUIRED_NODE_VERSION = NodeVersion.of(18, 0, 0);
    private static final NodeVersion INSTALLED_NODE_VERSION = NodeVersion.of(18, 16, 1);
    private static final String OUTPUT_DIRECTORY_VARIABLE_NAME = "CDK_OUTDIR";
    private static final String DEFAULT_ACCOUNT_VARIABLE_NAME = "CDK_DEFAULT_ACCOUNT";
    private static final String DEFAULT_REGION_VARIABLE_NAME = "CDK_DEFAULT_REGION";
    private static final String CONTEXT_VARIABLE_NAME = "CDK_CONTEXT_JSON";
    private static final String PATH_VARIABLE_NAME = "PATH";

    @Component
    private ToolchainManager toolchainManager;

    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /**
     * Current Maven session.
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    /**
     * Path to the local repository that will be used to store Node.js environment if it's not available to the plugin.
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    private File localRepositoryDirectory;

    /**
     * The name of the application class defining your cloud infrastructure. The application class must either extend
     * {@link software.amazon.awscdk.App} or define a main method which would create an instance of {@code App},
     * define the constructs associated with it and call {@link software.amazon.awscdk.App#synth()} method in order
     * to produce a cloud assembly with CloudFormation templates.
     */
    @Parameter(property = "aws.cdk.app", required = true)
    private String app;

    /**
     * Arguments to be passed to the application.
     */
    @Parameter(property = "aws.cdk.arguments")
    private List<String> arguments;

    private ProcessRunner processRunner;
    private Map<ContextProvider, ContextProviderMapper<?>> contextProviders;

    @Override
    public void execute(Path cloudAssemblyDirectory, Optional<String> profileOpt, Optional<String> endpointUrlOpt) {
        EnvironmentResolver environmentResolver = EnvironmentResolver.create(profileOpt.orElse(null), endpointUrlOpt);
        this.processRunner = new DefaultProcessRunner(project.getBasedir());
        this.contextProviders = initContextProviders(environmentResolver);
        synthesize(app, arguments != null ? arguments : Collections.emptyList(), cloudAssemblyDirectory, environmentResolver);
    }

    private Map<ContextProvider, ContextProviderMapper<?>> initContextProviders(EnvironmentResolver environmentResolver) {
        AwsClientProvider awsClientProvider = new AwsClientProviderBuilder()
                .withClientFactory(Ec2Client.class, env -> buildClient(Ec2Client.builder(), environmentResolver.resolve(env)))
                .withClientFactory(SsmClient.class, env -> buildClient(SsmClient.builder(), environmentResolver.resolve(env)))
                .withClientFactory(Route53Client.class, env -> {
                    ResolvedEnvironment resolvedEnvironment = environmentResolver.resolve(env);
                    return Route53Client.builder()
                            .region(Region.AWS_GLOBAL)
                            .credentialsProvider(StaticCredentialsProvider.create(resolvedEnvironment.getCredentials()))
                            .endpointOverride(resolvedEnvironment.getEndpointUriOpt().orElse(null))
                            .build();
                })
                .build();

        Map<ContextProvider, ContextProviderMapper<?>> contextProviders = new HashMap<>();
        contextProviders.put(ContextProvider.AVAILABILITY_ZONE_PROVIDER, new AvailabilityZonesContextProviderMapper(awsClientProvider));
        contextProviders.put(ContextProvider.SSM_PARAMETER_PROVIDER, new SsmContextProviderMapper(awsClientProvider));
        contextProviders.put(ContextProvider.HOSTED_ZONE_PROVIDER, new HostedZoneContextProviderMapper(awsClientProvider));
        contextProviders.put(ContextProvider.VPC_PROVIDER, new VpcNetworkContextProviderMapper(awsClientProvider));
        contextProviders.put(ContextProvider.AMI_PROVIDER, new AmiContextProviderMapper(awsClientProvider));
        return contextProviders;
    }

    private <B extends AwsClientBuilder<B, C>, C> C buildClient(B builder, ResolvedEnvironment environment) {
        return builder.region(environment.getRegion())
                .credentialsProvider(StaticCredentialsProvider.create(environment.getCredentials()))
                .endpointOverride(environment.getEndpointUriOpt().orElse(null))
                .build();
    }

    protected AssemblyManifest synthesize(String app, List<String> arguments, Path outputDirectory, EnvironmentResolver environmentResolver) {
        Map<String, String> environment;
        if (SystemUtils.IS_OS_WINDOWS) {
            environment = System.getenv().entrySet().stream()
                    .map(variable -> Pair.of(variable.getKey().toUpperCase(), variable.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else {
            environment = new HashMap<>(System.getenv());
        }

        NodeVersion nodeVersion = getInstalledNodeVersion().orElse(null);
        if (nodeVersion == null || nodeVersion.compareTo(MINIMUM_REQUIRED_NODE_VERSION) < 0) {
            if (nodeVersion == null) {
                logger.info("Node.js is not installed. Using Node.js from local Maven repository");
            } else {
                logger.info("The minimum required version of Node.js is {}, however {} is installed. Using the Node.js " +
                        "from the local Maven repository", MINIMUM_REQUIRED_NODE_VERSION, nodeVersion);
            }

            NodeClient node = getNodeInstaller().install(INSTALLED_NODE_VERSION);
            environment.compute(PATH_VARIABLE_NAME, (name, path) -> Stream.of(node.getPath().toString(), path)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(File.pathSeparator)));
        } else {
            logger.info("Using Node.js from path with version {}", nodeVersion);
        }

        environment.computeIfAbsent(OUTPUT_DIRECTORY_VARIABLE_NAME, v -> outputDirectory.toString());
        environment.computeIfAbsent(DEFAULT_REGION_VARIABLE_NAME, v -> environmentResolver.getDefaultRegion().id());
        if (environmentResolver.getDefaultAccount() != null) {
            environment.computeIfAbsent(DEFAULT_ACCOUNT_VARIABLE_NAME, v -> environmentResolver.getDefaultAccount());
        }

        Map<String, Object> context = readContext();

        logger.info("Synthesizing the cloud assembly for the '{}' application", app);
        AssemblyManifest cloudManifest = synthesize(app, arguments, outputDirectory, environment, context);

        boolean contextIsEmpty = true;
        while (cloudManifest.getMissing() != null && !cloudManifest.getMissing().isEmpty()) {
            context = Maps.newHashMap(context);
            for (MissingContext missingContext : cloudManifest.getMissing()) {
                ContextProvider provider = missingContext.getProvider();
                String key = missingContext.getKey();

                ContextProviderMapper contextProviderMapper = contextProviders.get(provider);
                if (contextProviderMapper == null) {
                    throw new CdkException("Unable to find a context provider for '" + provider +
                            "'. Please consider updating the version of the plugin");
                }

                Object contextProps = UnsafeCast.unsafeCast((JsiiObject) missingContext.getProps(), contextProviderMapper.getContextType());
                Object contextValue;
                try {
                    contextValue = contextProviderMapper.getContextValue(contextProps);
                } catch (Exception e) {
                    throw new CdkException("An error occurred while resolving context value for the " +
                            "key '" + key + "' using '" + provider + "' provider: " + e.getMessage());
                }
                if (contextValue == null) {
                    throw new CdkException("Unable to resolve context value for the key '" + key +
                            "' using '" + provider + "' provider");
                }
                contextIsEmpty = false;
                context.put(key, contextValue);
            }
            cloudManifest = synthesize(app, arguments, outputDirectory, environment, context);
        }

        if (!contextIsEmpty) {
            Path effectiveContextPath = outputDirectory.resolve("cdk.context.json");
            String contextStrPretty = new GsonBuilder()
                    .setPrettyPrinting()
                    .create()
                    .toJson(context);
            try {
                Files.write(effectiveContextPath, contextStrPretty.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                throw new CdkException("Unable to write effective context file to " + effectiveContextPath, e);
            }
        }

        logger.info("The cloud assembly has been successfully synthesized to {}", outputDirectory);
        return cloudManifest;
    }

    private Map<String, Object> readContext() {
        File contextFile = new File(project.getBasedir(), CDK_CONTEXT_FILE_NAME);

        Map<String, Object> context;
        if (contextFile.exists()) {
            try (Reader reader = new FileReader(contextFile)) {
                context = new Gson().fromJson(reader, new TypeToken<Map<String, Object>>() {
                }.getType());
            } catch (IOException e) {
                throw new CdkException("Unable to read the runtime context from the " + contextFile);
            }
        } else {
            context = Maps.newHashMap();
        }

        return context;
    }

    private AssemblyManifest synthesize(String app, List<String> arguments, Path outputDirectory, Map<String, String> environment, Map<String, Object> context) {
        Map<String, String> appEnvironment;
        if (context.isEmpty()) {
            appEnvironment = environment;
        } else {
            appEnvironment = ImmutableMap.<String, String>builder()
                    .putAll(environment)
                    .put(CONTEXT_VARIABLE_NAME, new Gson().toJson(context))
                    .build();
        }

        int exitCode;
        List<String> appExecutionCommand = buildAppExecutionCommand(app, arguments);
        ProcessContext processContext = ProcessContext.builder()
                .withEnvironment(appEnvironment)
                .build();
        try {
            exitCode = processRunner.run(appExecutionCommand, processContext);
        } catch (ProcessExecutionException e) {
            throw new CdkException("The synthesis has failed", e);
        }

        if (exitCode != 0 || !Files.exists(outputDirectory)) {
            throw new CdkException("The synthesis has failed: the output directory doesn't exist");
        }

        return Manifest.loadAssemblyManifest(outputDirectory.resolve("manifest.json").toString());
    }

    private List<String> buildAppExecutionCommand(String app, List<String> arguments) {
        String java = Optional.ofNullable(this.toolchainManager.getToolchainFromBuildContext("jdk", this.session))
                .map(toolchain -> toolchain.findTool("java"))
                .orElseGet(() -> System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = Streams.concat(
                project.getArtifacts().stream().map(Artifact::getFile).map(File::toString),
                Stream.of(project.getBuild().getOutputDirectory()),
                project.getResources().stream().map(FileSet::getDirectory),
                Stream.of(Synthesizer.class.getProtectionDomain().getCodeSource().getLocation().getFile())
        ).collect(Collectors.joining(File.pathSeparator));

        return Stream.concat(Stream.of(java, "-cp", classpath, Synthesizer.class.getName(), app), arguments.stream())
                .collect(Collectors.toList());
    }

    private Optional<NodeVersion> getInstalledNodeVersion() {
        try {
            return Optional.of(processRunner.run(ImmutableList.of("node", "--version")))
                    .flatMap(NodeVersion::parse);
        } catch (ProcessExecutionException e) {
            return Optional.empty();
        }
    }

    private NodeInstaller getNodeInstaller() {
        String osName = System.getProperty("os.name").toLowerCase();
        Path localRepositoryDirectory = this.localRepositoryDirectory.toPath();
        NodeInstaller nodeInstaller;

        if (osName.startsWith("Win".toLowerCase())) {
            nodeInstaller = new WindowsNodeInstaller(processRunner, localRepositoryDirectory);
        } else if (osName.startsWith("Mac".toLowerCase())) {
            nodeInstaller = new UnixNodeInstaller(processRunner, localRepositoryDirectory, "darwin");
        } else if (osName.startsWith("SunOS".toLowerCase())) {
            nodeInstaller = new UnixNodeInstaller(processRunner, localRepositoryDirectory, "sunos", "x64");
        } else if (osName.startsWith("Linux".toLowerCase())) {
            nodeInstaller = new UnixNodeInstaller(processRunner, localRepositoryDirectory, "linux");
        } else if (osName.startsWith("AIX".toLowerCase())) {
            nodeInstaller = new UnixNodeInstaller(processRunner, localRepositoryDirectory, "aix", "ppc64");
        } else {
            throw new NodeInstallationException("The platform is not supported: " + osName);
        }

        return nodeInstaller;
    }

}
