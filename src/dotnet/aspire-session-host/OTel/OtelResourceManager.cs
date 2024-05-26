using System.Collections.Concurrent;

namespace AspireSessionHost.OTel;

internal sealed class OTelResourceManager
{
    private readonly ConcurrentDictionary<string, OTelResource> _resources = new();

    internal OTelResource GetOrAddResource(string serviceName, string? serviceId)
    {
        var resourceId = serviceId ?? serviceName;
        var resource = _resources.GetOrAdd(resourceId, static id => new OTelResource(id));
        return resource;
    }
}