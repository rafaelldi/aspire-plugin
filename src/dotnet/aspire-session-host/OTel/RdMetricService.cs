using System.Threading.Channels;
using AspireSessionHost.Generated;
using JetBrains.Lifetimes;
using OpenTelemetry.Proto.Collector.Metrics.V1;
using OpenTelemetry.Proto.Resource.V1;

namespace AspireSessionHost.OTel;

internal sealed class RdMetricService(Connection connection) : IDisposable
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
                await ConsumeMetric(metric);
            }
        }
        catch (OperationCanceledException)
        {
            //do nothing
        }
    }

    private async Task ConsumeMetric(ExportMetricsServiceRequest metricRequest)
    {
        foreach (var resourceMetrics in metricRequest.ResourceMetrics)
        {
            var rdResource = MapResource(resourceMetrics.Resource);
            var rdMetrics = new RdOtelMetric[]{};
            var rdScopeMetrics = new RdOtelScopeMetrics(rdMetrics);
            var rdResourceMetrics = new RdOtelResourceMetrics(rdResource, rdScopeMetrics);

            await connection.DoWithModel(model =>
            {
                model.MetricReceived(rdResourceMetrics);
            });
        }
    }

    private static RdOtelResource MapResource(Resource resource)
    {
        var (serviceName, serviceId) = resource.GetServiceIdAndName();
        return new RdOtelResource(serviceName, serviceId);
    }

    public void Dispose()
    {
        _lifetimeDef.Dispose();
    }
}