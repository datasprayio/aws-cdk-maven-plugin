package io.dataspray.aws.cdk.context;

import com.google.common.collect.ImmutableList;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import software.amazon.awscdk.cloudassembly.schema.AvailabilityZonesContextQuery;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.AvailabilityZoneState;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.services.ec2.model.AvailabilityZoneState.*;

public class AvailabilityZonesContextProviderMapperTest {

    @DataProvider
    public Object[][] testDataProvider() {
        return new Object[][]{
                {
                        ImmutableList.of(),
                        new String[]{}
                },
                {
                        ImmutableList.of(
                                availabilityZone("us-west-2a", UNAVAILABLE),
                                availabilityZone("us-west-2b", UNAVAILABLE)
                        ),
                        new String[]{}
                },
                {
                        ImmutableList.of(
                                availabilityZone("us-west-2a", AVAILABLE),
                                availabilityZone("us-west-2b", AVAILABLE),
                                availabilityZone("us-west-2c", UNAVAILABLE),
                                availabilityZone("us-west-2d", AVAILABLE),
                                availabilityZone("us-west-2e", IMPAIRED),
                                availabilityZone("us-west-2f", INFORMATION)
                        ),
                        new String[]{
                                "us-west-2a",
                                "us-west-2b",
                                "us-west-2d"
                        }
                }
        };
    }

    @Test(dataProvider = "testDataProvider")
    public void test(List<AvailabilityZone> availabilityZones, String[] expectedValue) {
        Ec2Client ec2Client = mock(Ec2Client.class);
        DescribeAvailabilityZonesResponse response = DescribeAvailabilityZonesResponse.builder()
                .availabilityZones(availabilityZones)
                .build();
        when(ec2Client.describeAvailabilityZones())
                .thenReturn(response);

        AwsClientProvider clientProvider = mock(AwsClientProvider.class);
        when(clientProvider.getClient(any(), any()))
                .thenReturn(ec2Client);

        AvailabilityZonesContextProviderMapper contextProvider = new AvailabilityZonesContextProviderMapper(clientProvider);
        AvailabilityZonesContextQuery properties = AvailabilityZonesContextQuery.builder()
                .region("someRegion")
                .account("someAccount")
                .build();

        Object contextValue = contextProvider.getContextValue(properties);
        Assert.assertEquals(contextValue, expectedValue);
    }

    private AvailabilityZone availabilityZone(String zoneName, AvailabilityZoneState state) {
        return AvailabilityZone.builder()
                .zoneName(zoneName)
                .state(state)
                .build();
    }

}
