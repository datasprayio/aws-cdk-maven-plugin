package io.dataspray.aws.cdk;

import com.google.common.collect.Streams;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class StackDeployer {

    private static final Logger logger = LoggerFactory.getLogger(StackDeployer.class);

    private static final String BOOTSTRAP_VERSION_OUTPUT = "BootstrapVersion";
    private static final String BUCKET_NAME_OUTPUT = "BucketName";
    private static final String BUCKET_DOMAIN_NAME_OUTPUT = "BucketDomainName";
    private static final int MAX_TEMPLATE_SIZE = 50 * 1024;

    private final CloudFormationClient client;
    private final Path cloudAssemblyDirectory;
    private final ResolvedEnvironment environment;
    private final ToolkitConfiguration toolkitConfiguration;
    private final FileAssetPublisher fileAssetPublisher;
    private final DockerImageAssetPublisher dockerImagePublisher;
    private final Set<String> notificationArns;

    public StackDeployer(Path cloudAssemblyDirectory,
                         ResolvedEnvironment environment,
                         ToolkitConfiguration toolkitConfiguration,
                         FileAssetPublisher fileAssetPublisher,
                         DockerImageAssetPublisher dockerImagePublisher,
                         Set<String> notificationArns) {
        this.cloudAssemblyDirectory = cloudAssemblyDirectory;
        this.environment = environment;
        this.toolkitConfiguration = toolkitConfiguration;
        this.fileAssetPublisher = fileAssetPublisher;
        this.dockerImagePublisher = dockerImagePublisher;
        this.notificationArns = notificationArns;
        this.client = CloudFormationClientProvider.get(environment);
    }

    public Stack deploy(StackDefinition stackDefinition, Map<String, ParameterValue> assetParameters, Map<String, String> parameters, Map<String, String> tags) {
        String stackName = stackDefinition.getStackName();
        logger.info("Deploying '{}' stack", stackName);

        Map<String, ParameterValue> stackParameters = new HashMap<>();
        Stack deployedStack = Stacks.findStack(client, stackName).orElse(null);
        if (deployedStack != null) {
            if (Stacks.isInProgress(deployedStack)) {
                logger.info("Waiting until stack '{}' reaches stable state", deployedStack.stackName());
                deployedStack = awaitCompletion(deployedStack);
            }
            if (deployedStack.stackStatus() == StackStatus.ROLLBACK_COMPLETE || deployedStack.stackStatus() == StackStatus.ROLLBACK_FAILED) {
                logger.warn("The stack '{}' is in {} state after unsuccessful creation. The stack will be deleted " +
                        "and re-created.", stackName, deployedStack.stackStatus());
                deployedStack = Stacks.awaitCompletion(client, Stacks.deleteStack(client, deployedStack.stackName()));
            }
            if (Stacks.isFailed(deployedStack)) {
                throw StackDeploymentException.builder(stackName, environment)
                        .withCause("The stack '" + stackName + "' is in the failed state " + deployedStack.stackStatus())
                        .build();
            }
            if (deployedStack.stackStatus() != StackStatus.DELETE_COMPLETE) {
                deployedStack.parameters().forEach(p -> stackParameters.put(p.parameterKey(), ParameterValue.unchanged()));
            }
        }

        Streams.concat(stackDefinition.getParameterValues().entrySet().stream(), parameters.entrySet().stream())
                .filter(parameter -> parameter.getKey() != null && parameter.getValue() != null)
                .forEach(parameter -> stackParameters.put(parameter.getKey(), ParameterValue.value(parameter.getValue())));

        stackParameters.putAll(assetParameters);

        Map<String, ParameterValue> effectiveParameters = stackParameters.entrySet().stream()
                .filter(parameter -> stackDefinition.getParameters().containsKey(parameter.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        TemplateRef templateRef = getTemplateRef(stackDefinition);

        List<String> missingParameters = stackDefinition.getParameters().values().stream()
                .filter(parameterDefinition -> parameterDefinition.getDefaultValue() == null)
                .filter(parameterDefinition -> !effectiveParameters.containsKey(parameterDefinition.getName()))
                .map(ParameterDefinition::getName)
                .collect(Collectors.toList());

        if (!missingParameters.isEmpty()) {
            throw StackDeploymentException.builder(stackName, environment)
                    .withCause("The values for the following template parameters are missing: " + String.join(", ", missingParameters))
                    .build();
        }

        boolean updated = true;
        Stack stack;
        if (deployedStack != null && deployedStack.stackStatus() != StackStatus.DELETE_COMPLETE) {
            try {
                stack = Stacks.updateStack(client, stackName, templateRef, effectiveParameters, tags, notificationArns);
            } catch (CloudFormationException e) {
                AwsErrorDetails errorDetails = e.awsErrorDetails();
                if (!errorDetails.errorCode().equals("ValidationError") ||
                        !errorDetails.errorMessage().startsWith("No updates are to be performed")) {
                    throw e;
                }
                logger.info("No changes of the '{}' stack are detected. The deployment will be skipped", stackName);
                stack = deployedStack;
                updated = false;
            }
        } else {
            stack = Stacks.createStack(client, stackName, templateRef, effectiveParameters, tags, notificationArns);
        }

        if (updated) {
            if (!Stacks.isCompleted(stack)) {
                logger.info("Waiting until '{}' reaches stable state", stackName);
                stack = awaitCompletion(stack);
            }
            if (Stacks.isFailed(stack)) {
                throw StackDeploymentException.builder(stackName, environment)
                        .withCause("The deployment has failed: " + stack.stackStatus())
                        .build();
            }
            if (Stacks.isRolledBack(stack)) {
                throw StackDeploymentException.builder(stackName, environment)
                        .withCause("The deployment has been unsuccessful, the stack has been rolled back to its previous state")
                        .build();
            }
            logger.info("The stack '{}' has been successfully deployed", stackName);
        }

        return stack;
    }

    public ResolvedEnvironment getEnvironment() {
        return environment;
    }

    public ToolkitConfiguration getToolkitConfiguration() {
        return toolkitConfiguration;
    }

    private TemplateRef getTemplateRef(StackDefinition stackDefinition) {
        Map<String, Object> template = stackDefinition.getTemplate();
        String templateStr;
        try {
            templateStr = new Gson().toJson(template);
        } catch (Exception e) {
            throw StackDeploymentException.builder(stackDefinition.getStackName(), environment)
                    .withCause("Unable to parse template as json")
                    .withCause(e)
                    .build();
        }
        byte[] templateBytes = templateStr.getBytes(StandardCharsets.UTF_8);
        TemplateRef templateRef = null;

        if (templateBytes.length <= MAX_TEMPLATE_SIZE) {
            templateRef = TemplateRef.fromString(templateStr);
        }

        if (templateRef == null) {
            Toolkit toolkit = getToolkit(stackDefinition);
            String contentHash;
            try {
                contentHash = hash(templateBytes);
            } catch (IOException e) {
                throw StackDeploymentException.builder(stackDefinition.getStackName(), environment)
                        .withCause("Unable to hash the template")
                        .withCause(e)
                        .build();
            }

            String objectName = "cdk/" + stackDefinition.getStackName() + "/" + contentHash + ".json";

            try {
                fileAssetPublisher.publish(templateBytes, objectName, toolkit.getBucketName(), environment);
            } catch (IOException e) {
                throw StackDeploymentException.builder(stackDefinition.getStackName(), environment)
                        .withCause("An error occurred while uploading the template to the deployment bucket")
                        .withCause(e)
                        .build();
            } catch (CdkException e) {
                throw StackDeploymentException.builder(stackDefinition.getStackName(), environment)
                        .withCause(e.getMessage())
                        .withCause(e.getCause())
                        .build();
            } catch (Exception e) {
                throw StackDeploymentException.builder(stackDefinition.getStackName(), environment)
                        .withCause(e)
                        .build();
            }

            templateRef = TemplateRef.fromUrl("https://" + toolkit.getBucketDomainName() + "/" + objectName);
        }

        return templateRef;
    }

    public Optional<Stack> destroy(StackDefinition stackDefinition) {
        Stack stack = Stacks.findStack(client, stackDefinition.getStackName()).orElse(null);
        if (stack != null && stack.stackStatus() != StackStatus.DELETE_COMPLETE) {
            logger.info("The stack '${} is being deleted, awaiting until the operation is completed", stackDefinition.getStackName());
            stack = awaitCompletion(Stacks.deleteStack(client, stack.stackId()));
            if (stack.stackStatus() != StackStatus.DELETE_COMPLETE) {
                throw new CdkException("The deletion of '" + stackDefinition.getStackName() + "' stack has failed: " + stack.stackStatus());
            }
            logger.info("The stack '{}' has been successfully deleted", stack.stackName());
        } else {
            logger.warn("The generated template for the stack '{}' doesn't have any resources defined. The deployment " +
                    "will be skipped", stackDefinition.getStackName());
        }

        return Optional.ofNullable(stack);
    }

    private String hash(byte[] data) throws IOException {
        return ByteSource.wrap(data).hash(Hashing.sha256()).toString();
    }

    private Toolkit getToolkit(StackDefinition stack) {
        Stack toolkitStack = Stacks.findStack(client, toolkitConfiguration.getStackName()).orElse(null);
        if (toolkitStack != null && Stacks.isInProgress(toolkitStack)) {
            logger.info("Waiting until toolkit stack reaches stable state, environment={}, stackName={}",
                    environment, toolkitConfiguration.getStackName());
            toolkitStack = awaitCompletion(toolkitStack);
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
                        .withCause("The toolkit stack version '" + outputs.get(BOOTSTRAP_VERSION_OUTPUT) + "' is lower than"
                                + " the minimum version '" + stack.getRequiredToolkitStackVersion() + "' required by the stack."
                                + " Please update the toolkit stack or add 'bootstrap' goal to the plugin"
                                + " execution if you want the plugin to automatically create or update toolkit stack")
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

    private Stack awaitCompletion(Stack stack) {
        Stack completedStack;
        if (logger.isInfoEnabled()) {
            completedStack = Stacks.awaitCompletion(client, stack, new LoggingStackEventListener(Stacks.lastChange(stack)));
        } else {
            completedStack = Stacks.awaitCompletion(client, stack);
        }
        return completedStack;
    }

}
