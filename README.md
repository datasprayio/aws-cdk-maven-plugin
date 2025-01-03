<h1 align="center">AWS CDK for Java<br/>Maven Plugin | Standalone</h1>
<div align="center">
  <a href="https://github.com/datasprayio/aws-cdk-4j/actions?query=workflow%3A%22build%22">
    <img alt="Build Status" src="https://img.shields.io/github/actions/workflow/status/datasprayio/aws-cdk-4j/build.yml?style=for-the-badge">
  </a>
  <a href="https://search.maven.org/artifact/io.dataspray/aws-cdk">
    <img alt="Maven Central release" src="https://img.shields.io/maven-central/v/io.dataspray/aws-cdk?style=for-the-badge">
  </a>
  <a href="https://github.com/datasprayio/aws-cdk-4j/blob/master/COPYING">
    <img alt="License" src="https://img.shields.io/github/license/datasprayio/aws-cdk-4j?style=for-the-badge">
  </a>
</div>
<h3 align="center">Synthesize, bootstrap and deploy without Node.js nor CDK Toolkit</h3>

### Contents

- [Getting Started](#getting-started)
    - [As a standalone library](#as-a-standalone-library)
    - [As a Maven plugin](#as-a-maven-plugin)
- [Actions documentation](#actions-documentation)
    - [Synthesize](#synthesize)
    - [Bootstrap](#bootstrap)
    - [Deployment](#deploy)
    - [Destroy](#destroy)
- [Authentication](#authentication)
- [AWS CDK Dependency bump](#aws-cdk-dependency-bump)
- [Migration from LinguaRobot](#migration-from-linguarobot)
- [Security Policy](#security-policy)

# Getting Started

Requirements:

| Depdenency                | Version    |
|---------------------------|------------|
| Java                      | >= 11      |
| Maven                     | >= 3.5     |
| AWS CDK                   | <= 2.173.4 |
| AWS Cloud Assembly Schema | <= 39.1.35 |

_To bump up, open an issue or [see here](#aws-cdk-dependency-bump)_

## As a standalone library

Import the library using Maven:

```xml
<dependency>
    <groupId>io.dataspray</groupId>
    <artifactId>aws-cdk</artifactId>
    <version><!-- Latest version: https://search.maven.org/artifact/io.dataspray/aws-cdk --></version>
</dependency>
```

And use it directly with your CDK stacks:

```java
// Build your stack
CloudAssembly assembly = app.synth();

// Bootstrap
AwsCdk.bootstrap().execute(cloudAssembly);

// Deploy
AwsCdk.deploy().execute(cloudAssembly);

// Destroy
AwsCdk.destroy().execute(cloudAssembly);
```

## As a Maven plugin

You can also perform actions using our Maven Plugin:

```xml
<plugin>
    <groupId>io.dataspray</groupId>
    <artifactId>aws-cdk-maven-plugin</artifactId>
    <version><!-- Latest version: https://search.maven.org/artifact/io.dataspray/aws-cdk-maven-plugin --></version>
    <executions>
        <execution>
            <id>deploy-cdk-app</id>
            <goals>
                <goal>synth</goal>
                <goal>bootstrap</goal>
                <goal>deploy</goal>
                <!-- <goal>destroy</goal> -->
            </goals>
            <configuration>
                <!-- Full class name of the app class defining your stacks -->
                <app>${cdk.app}</app>
                <!-- Input parameters for the stacks. -->
                <parameters>
                    <ParameterName>...</ParameterName>
                    ...
                </parameters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Please take a look at the [example project][6]. It is based on the project generated using `cdk init` with the
difference that it uses `aws-cdk-maven-plugin` instead of the CDK CLI. You can also find more examples in the
[integration test](./aws-cdk-integration-tests/src/it) directory.

# Actions documentation

There are several actions you can perform using this library or Maven plugin outlined below:

| Action | Plugin Goal | Library | Description |
| --- | --- | --- | --- |
| [Synthesize](#synthesize) | `synth` | `software.amazon.awscdk.` `App.synth()` | Synthesizes [CloudFormation][4] templates based on the resources defined in your CDK application. |
| [Bootstrap](#bootstrap) | `bootstrap` | `io.dataspray.aws.cdk.` `AwsCdk.bootstrap()` | Deploys toolkit stacks required by the CDK application to an AWS. |
| [Deploy](#deploy) | `deploy` | `io.dataspray.aws.cdk.` `AwsCdk.deploy()` | Deploys the CDK application to an AWS (based on the synthesized resources) |
| [Destroy](#destroy) | `destroy` | `io.dataspray.aws.cdk.` `AwsCdk.destroy()` | Destroys the CDK application from AWS |

## Synthesize

### Library

Synthesize your stack using the AWS CDK library as normal using `app.synth()` which will produce
a `software.amazon.awscdk.cxapi.CloudAssembly`. This `CloudAssembly` can be used for subsequent actions to bootstrap,
deploy and destroy a stack.

```java
App app = new App();
new MyStack(app);
CloudAssembly assembly = app.synth();
```

### Maven Plugin

During the execution of `synth` goal, a cloud assembly is synthesized. The cloud assembly is a directory
(`target/cdk.out` by default) containing the artifacts required for the deployment, i.e. CloudFormation templates, AWS
Lambda bundles, file and Docker image assets etc. The artifacts in the cloud assembly directory are later used by
`bootstrap` and `deploy` goals.

The only mandatory parameter required by the goal is `<app>`, which is a full class name of the CDK app class defining
the cloud infrastructure. The application class must either extend `software.amazon.awscdk.App` or define a
`main` method which is supposed to create an instance of `App`, define cloud [constructs][5] and call `App#synth()`
method in order to produce a cloud assembly with CloudFormation templates.

Extending `App` class:

```java
import software.amazon.awscdk.App;

public class MyApp extends App {

    public Mypp() {
        new MyStack(this, "my-stack");
    }

}
```

Defining `main` method:

```java
import software.amazon.awscdk.App;

public class MyApp {

    public static void main(String[] args) {
        App app = new App();
        new MyStack(app, "my-stack");
        app.synth();
    }
    
}
```

### Configuration

| Parameter | Type | Since | Description |
| --- | --- | --- | --- |
| `<app>` <br/> `-Daws.cdk.app` | `String` | `0.0.1` | Full class name of the CDK app class defining the cloud infrastructure. |
| `<profile>` <br/> `-Daws.cdk.profile` | `String` | `0.0.1` | A profile that will be used to find credentials and region. |
| `<cloudAssemblyDirectory>` <br/> `-Daws.cdk.cloud.assembly.directory` | `String` | `0.0.1` | A directory where the cloud assembly will be synthesized. |
| `<arguments>` <br/> `-Daws.cdk.arguments` | `List<String>` | `0.0.5` | A list of arguments to be passed to the CDK application. |
| `<skip>` <br/> `-Daws.cdk.skip` | `boolean` | `0.0.7` | Enables/disables the execution of the goal. | 

## Bootstrap

CDK applications require a "toolkit stack" that includes the resources required for the application operation. For
example, the toolkit stack may include S3 bucket used to store templates and assets for the deployment.

You may also choose to omit bootstrapping if you don't want to rely on the plugin and control this process by yourself.
If you choose to omit, you will need to install the toolkit stack the first time you deploy an AWS CDK application into
an environment (account/region) by running `cdk bootstrap` command (please refer to [AWS CDK Toolkit][3] for the
details).

### Library

Passing in a cloud assembly, the toolkit stack will be deployed in all the environemnts the stacks reside in.

```java
AwsCdk.bootstrap().execute(cloudAssembly, "myStack1", "myStack2");
```

### Maven Plugin

The plugin will automatically deploy the toolkit stack
(or update if needed) during the execution of `bootstrap` goal (provided that the required toolkit stack version wasn't
already deployed).

### Configuration

| Parameter | Type | Since | Description |
| --- | --- | --- | --- |
| `String profile` <br/> `<profile>` <br/> `-Daws.cdk.profile` | `String` | `0.0.1` | A profile that will be used to find credentials and region. |
| `CloudAssembly cloudAssembly` <br/> `Path cloudAssemblyDirectory` <br/> `<cloudAssemblyDirectory>` <br/> `-Daws.cdk.cloud.assembly.directory` | `String` | `0.0.1` | A cloud assembly directory with the deployment artifacts (`target/cdk.out` by default). Using the library, you can also pass the `CloudAssembly` directly. |
| `String toolkitStackName` <br/> `<toolkitStackName>` <br/> `-Daws.cdk.toolkit.stack.name` | `String` | `0.0.1` | The name of the CDK toolkit stack to use (`CDKToolkit` is used by default). |
| `Map<String, String> bootstrapParameters` <br/> `<bootstrapParameters>` | `Map<String, String>` | `1.2.0` | Input parameters for the bootstrap stack. In the case of an update, existing values will be reused. |
| `Map<String, String> bootstrapTags` <br/> `<bootstrapTags>` | `Map<String, String>` | `1.2.0` | Tags that will be added to the bootstrap stack. |
| `Set<String> stacks` <br/> `<stacks>` <br/> `-Daws.cdk.stacks` | `List<String>` | `0.0.4` | Stacks to deploy. By default, all the stacks defined in your application will be deployed. |
| `<skip>` <br/> `-Daws.cdk.skip` | `boolean` | `0.0.7` | Enables/disables the execution of the goal. |

## Deploy

### Library

To deploy a stack from either a synthesized application in a directory or directly from `app.synth()`` do so like this:

```java
CloudAssembly assembly = app.synth();
AwsCdk.deploy().execute(cloudAssembly, "myStack1", "myStack2");
```

```java
AwsCdk.deploy().execute(Path.of("/path/to/cdk.out"), "myStack1", "myStack2");
```

### Maven Plugin

To deploy the synthesized application into an AWS, add `deploy` goal to the execution (`deploy` and `bootstrap` goals
are attached to the `deploy` Maven phase).

### Configuration

| Parameter                                                                                                                                     | Type                  | Since   | Description                                                                                                                                                                  |
|-----------------------------------------------------------------------------------------------------------------------------------------------|-----------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `String profile` <br/> `<profile>` <br/> `-Daws.cdk.profile`                                                                                  | `String`              | `0.0.1` | A profile that will be used to find credentials and region.                                                                                                                  |
| `CloudAssembly cloudAssembly` <br/> `Path cloudAssemblyDirectory` <br/> `<cloudAssemblyDirectory>` <br/> `-Daws.cdk.cloud.assembly.directory` | `String`              | `0.0.1` | A cloud assembly directory with the deployment artifacts (`target/cdk.out` by default). Using the library, you can also pass the `CloudAssembly` directly.                   |
| `String toolkitStackName` <br/> `<toolkitStackName>` <br/> `-Daws.cdk.toolkit.stack.name`                                                     | `String`              | `0.0.1` | The name of the CDK toolkit stack to use (`CDKToolkit` is used by default).                                                                                                  |
| `Set<String> stacks` <br/> `<stacks>` <br/> `-Daws.cdk.stacks`                                                                                | `List<String>`        | `0.0.4` | Stacks to deploy. By default, all the stacks defined in your application will be deployed.                                                                                   |
| `Map<String, String> parameters` <br/> `<parameters>`                                                                                         | `Map<String, String>` | `0.0.4` | Input parameters for the stacks. For the new stacks, all the parameters without a default value must be specified. In the case of an update, existing values will be reused. |
| `Map<String, String> tags` <br/> `<tags>`                                                                                                     | `Map<String, String>` | `1.1.0` | Tags to be applied for all stacks.                                                                                                                                           |
| `Set<String> notificationArns` <br/> `<notificationArns>`                                                                                     | `Set<String>`         | `2.1.0` | SNS ARNs to publish stack related events.                                                                                                                                    |
| `<skip>` <br/> `-Daws.cdk.skip`                                                                                                               | `boolean`             | `0.0.7` | Enables/disables the execution of the goal.                                                                                                                                  |

## Destroy

### Library

To destroy a stack from either a synthesized application in a directory or directly from `app.synth()`` do so like this:

```java
CloudAssembly assembly = app.synth();
AwsCdk.destroy().execute(cloudAssembly, "myStack1", "myStack2");
```

```java
AwsCdk.destroy().execute(Path.of("/path/to/cdk.out"), "myStack1", "myStack2");
```

### Maven Plugin

To destroy an existing application into an AWS, add `destroy` goal to the execution.

#### Configuration

| Parameter | Type | Since | Description |
| --- | --- | --- | --- |
| `String profile` <br/> `<profile>` <br/> `-Daws.cdk.profile` | `String` | `0.0.1` | A profile that will be used to find credentials and region. |
| `CloudAssembly cloudAssembly` <br/> `Path cloudAssemblyDirectory` <br/> `<cloudAssemblyDirectory>` <br/> `-Daws.cdk.cloud.assembly.directory` | `String` | `0.0.1` | A cloud assembly directory with the deployment artifacts (`target/cdk.out` by default). Using the library, you can also pass the `CloudAssembly` directly. |
| `Set<String> stacks` <br/> `<stacks>` <br/> `-Daws.cdk.stacks` | `List<String>` | `0.0.4` | Stacks to deploy. By default, all the stacks defined in your application will be deployed. |
| `<skip>` <br/> `-Daws.cdk.skip` | `boolean` | `0.0.7` | Enables/disables the execution of the goal. |

# Authentication

The plugin tries to find the credentials and region in different sources in the following order:

* If `profile` configuration parameter is defined, the plugin looks for the corresponding credentials and region in the
  default AWS credentials and config files (`~/.aws/credentials` and `~/.aws/config`, the location may be different
  depending on the platform).
* Using Java system properties `aws.accessKeyId`, `aws.secretKey` and `aws.region`.
* Using environment variables `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_DEFAULT_REGION`
* Looking for the credentials and region associated with the default profile in the credentials and config files.

# AWS CDK Dependency bump

In order to use the latest AWS CDK, this repository needs to be updated to support your version. This section describes
the process of bumping these dependencies.

## Bump the AWS versions

In [pom.xml](pom.xml), bump the following versions: `aws.cdk.version`, `aws.sdk.version`, `aws.cdk.jsii.version`.

At the top of this README, update the CDK version and the version of the cloud assembly schema version that can
be [found here](https://github.com/aws/aws-cdk/blob/dffedca154f7cb31a5cefc24c638ad069577c836/packages/aws-cdk-lib/cloud-assembly-schema/schema/cloud-assembly.version.json).

## Bump the Bootstrap Stack version

In [BootstrampImpl.java](aws-cdk/src/main/java/io/dataspray/aws/cdk/BootstrapImpl.java), under the
`TOOLKIT_STACK_VERSION` constant, there are instructions on how to bring the latest version of the bootstrap stack.

# Migration from LinguaRobot

This library is originally based off
of [LinguaRobot/aws-cdk-maven-plugin](https://github.com/LinguaRobot/aws-cdk-maven-plugin). Migrating from LinguaRobot
Maven Plugin is simple as changing the Plugin's groupId from `io.linguarobot` to `io.dataspray` and bumping to the
latest version from Maven Central.

# Security Policy

## Reporting a Vulnerability

Please report to security@smotana.com for all vulnerabilities or questions regarding security.

[1]: https://aws.amazon.com/cdk/

[2]: https://nodejs.org/en/download

[3]: https://docs.aws.amazon.com/cdk/latest/guide/tools.html#cli

[4]: https://aws.amazon.com/cloudformation/

[5]: https://docs.aws.amazon.com/cdk/latest/guide/constructs.html

[6]: https://github.com/datasprayio/aws-cdk-4j-maven-plugin-example
