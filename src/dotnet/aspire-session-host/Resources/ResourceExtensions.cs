using System.Globalization;
using Aspire.V1;
using AspireSessionHost.Generated;
using Google.Protobuf.Collections;
using Google.Protobuf.WellKnownTypes;
using ResourceProperty = AspireSessionHost.Generated.ResourceProperty;
using ResourceType = AspireSessionHost.Generated.ResourceType;

namespace AspireSessionHost.Resources;

internal static class ResourceExtensions
{
    internal static ResourceModel ToModel(this Resource resource) => new(
        resource.Name,
        MapType(resource.ResourceType),
        resource.DisplayName,
        resource.Uid,
        resource.HasState ? MapState(resource.State) : null,
        resource.HasStateStyle ? MapStyle(resource.StateStyle) : null,
        resource.CreatedAt.ToDateTime(),
        resource.Properties.Select(it => it.ToModel()).ToArray(),
        resource.Environment.Select(it => it.ToModel()).ToArray(),
        resource.Urls.Select(it => it.ToModel()).ToArray(),
        GetOTelResourceId(resource.Environment)
    );

    private static ResourceType MapType(string type) => type switch
    {
        "Project" => ResourceType.Project,
        "Container" => ResourceType.Container,
        "Executable" => ResourceType.Executable,
        _ => ResourceType.Unknown
    };

    private static ResourceState MapState(string state) => state switch
    {
        "Finished" => ResourceState.Finished,
        "Exited" => ResourceState.Exited,
        "FailedToStart" => ResourceState.FailedToStart,
        "Starting" => ResourceState.Starting,
        "Running" => ResourceState.Running,
        "Hidden" => ResourceState.Hidden,
        _ => ResourceState.Unknown
    };

    private static ResourceStateStyle MapStyle(string style) => style switch
    {
        "success" => ResourceStateStyle.Success,
        "info" => ResourceStateStyle.Info,
        "warning" => ResourceStateStyle.Warning,
        "error" => ResourceStateStyle.Error,
        _ => ResourceStateStyle.Unknown
    };

    private static ResourceProperty ToModel(this Aspire.V1.ResourceProperty property) => new(
        property.Name,
        property.HasDisplayName ? property.DisplayName : null,
        GetStringValue(property.Value)
    );

    private static string? GetStringValue(Value value)
    {
        if (value.HasStringValue)
        {
            return value.StringValue;
        }

        if (value.HasBoolValue)
        {
            return value.BoolValue.ToString();
        }

        if (value.HasNumberValue)
        {
            return value.NumberValue.ToString(CultureInfo.InvariantCulture);
        }

        if (value.HasNullValue)
        {
            return null;
        }

        return value.ToString();
    }

    private static ResourceEnvironmentVariable ToModel(this EnvironmentVariable variable) => new(
        variable.Name,
        variable.HasValue ? variable.Value : null
    );

    private static ResourceUrl ToModel(this Url url) => new(
        url.Name,
        url.FullUrl,
        url.IsInternal
    );

    private static string? GetOTelResourceId(RepeatedField<EnvironmentVariable> variables)
    {
        var resourceAttributes = variables.FirstOrDefault(v => v.Name == "OTEL_RESOURCE_ATTRIBUTES");
        if (resourceAttributes is null || !resourceAttributes.HasValue) return null;

        var attributes = resourceAttributes.Value.Split(",");
        string? serviceInstanceId = null;
        string? serviceName = null;

        foreach (var attribute in attributes)
        {
            if (attribute.StartsWith("service.instance.id="))
            {
                serviceInstanceId = attribute.Substring(20);
                break;
            }

            if (attribute.StartsWith("service.name="))
            {
                serviceName = attribute.Substring(13);
            }
        }

        return serviceInstanceId ?? serviceName;
    }
}