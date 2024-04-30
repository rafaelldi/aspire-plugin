using System.Threading.Channels;
using AspireSessionHost.Generated;
using JetBrains.Lifetimes;
using OpenTelemetry.Proto.Collector.Metrics.V1;
using OpenTelemetry.Proto.Metrics.V1;
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
            var rdSCopeMetrics = new List<RdOtelScopeMetrics>(resourceMetrics.ScopeMetrics.Count);
            foreach (var scopeMetrics in resourceMetrics.ScopeMetrics)
            {
                var scopeName = scopeMetrics.Scope.Name;
                var rdMetrics = new List<RdOtelMetric>(scopeMetrics.Metrics.Count);
                foreach (var metric in scopeMetrics.Metrics)
                {
                    var rdMetric = MapMetric(metric);
                    rdMetrics.Add(rdMetric);
                }

                var rdScopeMetrics = new RdOtelScopeMetrics(scopeName, rdMetrics.ToArray());
                rdSCopeMetrics.Add(rdScopeMetrics);
            }

            var rdResourceMetrics = new RdOtelResourceMetrics(rdResource, rdSCopeMetrics.ToArray());

            await connection.DoWithModel(model => { model.MetricReceived(rdResourceMetrics); });
        }
    }

    private static RdOtelResource MapResource(Resource resource)
    {
        var (serviceName, serviceId) = resource.GetServiceIdAndName();
        return new RdOtelResource(serviceName, serviceId);
    }

    private static RdOtelMetric MapMetric(Metric metric)
    {
        return new RdOtelMetric(
            metric.Name,
            metric.Description,
            metric.Unit,
            MapMetricType(metric)
        );
    }

    private static RdOtelMetricType MapMetricType(Metric metric) => metric.DataCase switch
    {
        Metric.DataOneofCase.Gauge => RdOtelMetricType.Gauge,
        Metric.DataOneofCase.Sum => RdOtelMetricType.Sum,
        Metric.DataOneofCase.Histogram => RdOtelMetricType.Histogram,
        _ => RdOtelMetricType.Unknown
    };

    public void Dispose()
    {
        _lifetimeDef.Dispose();
    }
}