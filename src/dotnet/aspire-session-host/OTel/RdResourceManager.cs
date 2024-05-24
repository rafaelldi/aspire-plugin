using System.Collections.Concurrent;
using AspireSessionHost.Generated;

namespace AspireSessionHost.OTel;

internal sealed class RdResourceManager
{
    private readonly ConcurrentDictionary<string, RdResource> _resources = new();

    internal RdResource GetOrAddResource(string serviceName, string? serviceId)
    {
        var resourceId = serviceId ?? serviceName;
        var resource = _resources.GetOrAdd(resourceId, static id => new RdResource(id));
        return resource;
    }

    internal ResourceMetricId[] GetResourceMetrics(string resourceId)
    {
        if (!_resources.TryGetValue(resourceId, out var resource)) return [];

        return resource.GetMetricIds();
    }
}