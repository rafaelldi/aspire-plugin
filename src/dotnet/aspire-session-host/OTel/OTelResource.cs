using AspireSessionHost.Generated;
using OpenTelemetry.Proto.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class OTelResource(string id)
{
    private readonly object _lockObj = new();
    private readonly Dictionary<ResourceMetricId, OTelMetric> _metrics = new();
    
    internal string Id() => id;

    internal (OTelMetric metric, bool added) AddMetric(ResourceMetricId metricId, Metric metric)
    {
        lock (_lockObj)
        {
            if (_metrics.TryGetValue(metricId, out var existingMetric))
            {
                return (existingMetric, false);
            }

            var newMetric = new OTelMetric(
                metricId.ScopeName,
                metricId.MetricName,
                Map(metric.DataCase),
                metric.Description,
                metric.Unit
            );
            _metrics.TryAdd(metricId, newMetric);

            return (newMetric, true);
        }
    }

    private static OTelMetricType Map(Metric.DataOneofCase type) => type switch
    {
        Metric.DataOneofCase.Gauge => OTelMetricType.Gauge,
        Metric.DataOneofCase.Sum => OTelMetricType.Sum,
        Metric.DataOneofCase.Histogram => OTelMetricType.Histogram,
        _ => OTelMetricType.Other
    };
}