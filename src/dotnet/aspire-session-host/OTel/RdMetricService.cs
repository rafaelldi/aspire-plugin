using System.Threading.Channels;
using JetBrains.Lifetimes;
using OpenTelemetry.Proto.Collector.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class RdMetricService(RdResourceManager resourceManager) : IDisposable
{
    private readonly LifetimeDefinition _lifetimeDef = new();

    private readonly Channel<ExportMetricsServiceRequest> _channel = Channel.CreateBounded<ExportMetricsServiceRequest>(
        new BoundedChannelOptions(100)
        {
            SingleReader = true,
            SingleWriter = true,
            FullMode = BoundedChannelFullMode.DropOldest
        });

    internal void Initialize()
    {
        _lifetimeDef.Lifetime.StartAttachedAsync(TaskScheduler.Default, async () => await ConsumeMetrics());
    }

    internal void Send(ExportMetricsServiceRequest request)
    {
        _channel.Writer.TryWrite(request);
    }

    private async Task ConsumeMetrics()
    {
        try
        {
            await foreach (var metric in _channel.Reader.ReadAllAsync(Lifetime.AsyncLocal.Value))
            {
                ConsumeMetric(metric);
            }
        }
        catch (OperationCanceledException)
        {
            //do nothing
        }
    }

    private void ConsumeMetric(ExportMetricsServiceRequest metricRequest)
    {
        foreach (var resourceMetrics in metricRequest.ResourceMetrics)
        {
            var (serviceName, serviceId) = resourceMetrics.Resource.GetServiceIdAndName();
            var resource = resourceManager.GetOrAddResource(serviceName, serviceId);
            resource.AddMetrics(resourceMetrics.ScopeMetrics);
        }
    }

    public void Dispose()
    {
        _lifetimeDef.Dispose();
    }
}