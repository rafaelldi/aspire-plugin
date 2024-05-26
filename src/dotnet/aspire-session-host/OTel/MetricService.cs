using System.Threading.Channels;
using AspireSessionHost.Generated;
using JetBrains.Collections.Viewable;
using JetBrains.Lifetimes;
using OpenTelemetry.Proto.Collector.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class MetricService(OTelResourceManager resourceManager, Connection connection) : IDisposable
{
    private readonly LifetimeDefinition _lifetimeDef = new();

    private readonly Channel<ExportMetricsServiceRequest> _channel = Channel.CreateBounded<ExportMetricsServiceRequest>(
        new BoundedChannelOptions(100)
        {
            SingleReader = true,
            SingleWriter = true,
            FullMode = BoundedChannelFullMode.DropOldest
        });

    private readonly HashSet<ResourceMetricId> _metricValueSubscriptions = new();

    internal async Task Initialize()
    {
        _lifetimeDef.Lifetime.StartAttachedAsync(TaskScheduler.Default, async () => await ConsumeMetrics());

        await connection.DoWithModel(model =>
        {
            model.MetricSubscriptions.View(_lifetimeDef.Lifetime, ViewMetricSubscription);
        });
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
            var (serviceName, serviceId) = resourceMetrics.Resource.GetServiceIdAndName();
            var resource = resourceManager.GetOrAddResource(serviceName, serviceId);
            foreach (var scopeMetrics in resourceMetrics.ScopeMetrics)
            {
                foreach (var metric in scopeMetrics.Metrics)
                {
                    var metricId = new ResourceMetricId(resource.Id(), scopeMetrics.Scope.Name, metric.Name);
                    var (oTelMetric, isAdded) = resource.AddMetric(metricId, metric);
                    if (isAdded)
                    {
                        await connection.DoWithModel(model => { model.MetricIds.Add(metricId); });
                    }

                    oTelMetric.AddMetricValue(metric);
                    if (_metricValueSubscriptions.Contains(metricId))
                    {
                        var resourceMetric = new ResourceMetric(metricId, 1.0, 1716751794002);
                        await connection.DoWithModel(model => { model.MetricReceived(resourceMetric); });
                    }
                }
            }
        }
    }

    private void ViewMetricSubscription(Lifetime lifetime, int index, ResourceMetricId metricId)
    {
        _metricValueSubscriptions.AddLifetimed(lifetime, metricId);
    }

    public void Dispose()
    {
        _lifetimeDef.Dispose();
    }
}