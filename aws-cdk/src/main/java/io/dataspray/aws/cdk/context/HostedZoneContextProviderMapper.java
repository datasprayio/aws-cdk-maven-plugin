package io.dataspray.aws.cdk.context;

import com.google.common.collect.ImmutableMap;
import io.dataspray.aws.cdk.CdkException;
import software.amazon.awscdk.cloudassembly.schema.HostedZoneContextQuery;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class HostedZoneContextProviderMapper implements ContextProviderMapper<HostedZoneContextQuery> {

    private final AwsClientProvider awsClientProvider;

    public HostedZoneContextProviderMapper(AwsClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    @Override
    public Object getContextValue(HostedZoneContextQuery properties) {
        String environment = ContextProviders.buildEnvironment(properties.getAccount(), properties.getRegion());
        String domainName = getDomainName(properties);
        boolean isPrivate = Boolean.TRUE.equals(properties.getPrivateZone());
        String vpcId = properties.getVpcId();

        try (Route53Client route53Client = awsClientProvider.getClient(Route53Client.class, environment)) {
            ListHostedZonesByNameRequest zoneListRequest = ListHostedZonesByNameRequest.builder()
                    .dnsName(domainName)
                    .build();

            List<HostedZone> matchedHostedZones = Stream.of(route53Client.listHostedZonesByName(zoneListRequest))
                    .filter(ListHostedZonesByNameResponse::hasHostedZones)
                    .flatMap(response -> response.hostedZones().stream())
                    .filter(zone -> zone.name().equals(domainName))
                    .filter(zone -> isPrivate == isPrivate(zone))
                    .filter(zone -> {
                        if (vpcId == null) {
                            return true;
                        }

                        GetHostedZoneRequest zoneRequest = GetHostedZoneRequest.builder()
                                .id(zone.id())
                                .build();

                        return Stream.of(route53Client.getHostedZone(zoneRequest))
                                .filter(GetHostedZoneResponse::hasVpCs)
                                .flatMap(response -> response.vpCs().stream())
                                .anyMatch(vpc -> vpc.vpcId().equals(vpcId));
                    })
                    .collect(Collectors.toList());

            if (matchedHostedZones.size() != 1) {
                throw new CdkException("Found " + matchedHostedZones.size() + " hosted zones matching the " +
                        "criteria, however exactly 1 is required");
            }

            HostedZone hostedZone = matchedHostedZones.get(0);
            return ImmutableMap.of(
                    "Id", hostedZone.id(),
                    "Name", hostedZone.name());
        }

    }

    @Override
    public Class<HostedZoneContextQuery> getContextType() {
        return HostedZoneContextQuery.class;
    }

    private boolean isPrivate(HostedZone hostedZone) {
        return hostedZone.config() != null &&
                hostedZone.config().privateZone() != null &&
                hostedZone.config().privateZone();
    }

    private String getDomainName(HostedZoneContextQuery hostedZoneContextQuery) {
        String domainName = hostedZoneContextQuery.getDomainName();
        return domainName.endsWith(".") ? domainName : domainName + ".";
    }

}
