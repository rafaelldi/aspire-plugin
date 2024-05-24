using System.Collections.Concurrent;
using AspireSessionHost.Generated;
using Google.Protobuf.Collections;
using OpenTelemetry.Proto.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class RdResource(string id)
{
    private readonly ConcurrentDictionary<RdMetricId, RdMetric> _metrics = new();

    internal void AddMetrics(RepeatedField<ScopeMetrics> scopeMetricsList)
    {
        foreach (var scopeMetrics in scopeMetricsList)
        {
            foreach (var metric in scopeMetrics.Metrics)
            {
                var id = new RdMetricId(scopeMetrics.Scope.Name, metric.Name);
                var rdMetric = _metrics.GetOrAdd(
                    id,
                    static (metricId, otlpMetric) =>
                        new RdMetric(
                            metricId,
                            Map(otlpMetric.DataCase),
                            otlpMetric.Description,
                            otlpMetric.Unit
                        ),
                    metric
                );

                if (rdMetric.Type is RdMetricType.Other) continue;

                rdMetric.AddMetricValue(metric);
            }
        }
    }

    private static RdMetricType Map(Metric.DataOneofCase type) => type switch
    {
        Metric.DataOneofCase.Gauge => RdMetricType.Gauge,
        Metric.DataOneofCase.Sum => RdMetricType.Sum,
        Metric.DataOneofCase.Histogram => RdMetricType.Histogram,
        _ => RdMetricType.Other
    };

    public ResourceMetricId[] GetMetricIds()
    {
        var ids = _metrics.Keys.Select(it => new ResourceMetricId(it.ScopeName, it.MetricName)).ToArray();
        return ids;
    }
}