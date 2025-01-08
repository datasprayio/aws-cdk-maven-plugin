package io.dataspray.aws.cdk.context;

import io.dataspray.aws.cdk.CdkException;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awscdk.cloudassembly.schema.SSMParameterContextQuery;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class SsmContextProviderMapperTest {

    @Test
    public void test() {
        SsmClient ssmClient = mock(SsmClient.class);
        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenReturn(GetParameterResponse.builder().parameter(parameter("name", "value")).build());

        AwsClientProvider awsClientProvider = Mockito.mock(AwsClientProvider.class);
        when(awsClientProvider.getClient(any(), any()))
                .thenReturn(ssmClient);

        SsmContextProviderMapper ssmContextProvider = new SsmContextProviderMapper(awsClientProvider);

        SSMParameterContextQuery properties = SSMParameterContextQuery.builder()
                .region("someRegion")
                .account("someAccount")
                .parameterName("name")
                .build();
        Object contextValue = ssmContextProvider.getContextValue(properties);
        Assert.assertEquals(contextValue, "value");
    }

    @Test(expectedExceptions = CdkException.class)
    public void testParameterNotFound() {
        SsmClient ssmClient = mock(SsmClient.class);

        when(ssmClient.getParameter(any(GetParameterRequest.class)))
                .thenThrow(ParameterNotFoundException.builder().build());

        AwsClientProvider awsClientProvider = Mockito.mock(AwsClientProvider.class);
        when(awsClientProvider.getClient(any(), any()))
                .thenReturn(ssmClient);

        SSMParameterContextQuery properties = SSMParameterContextQuery.builder()
                .region("someRegion")
                .account("someAccount")
                .parameterName("name")
                .build();
        new SsmContextProviderMapper(awsClientProvider).getContextValue(properties);
    }

    private Parameter parameter(String name, String value) {
        return Parameter.builder()
                .name(name)
                .value(value)
                .build();
    }

}
