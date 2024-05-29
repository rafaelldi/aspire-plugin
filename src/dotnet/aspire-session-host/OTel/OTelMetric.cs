using System.Collections.Concurrent;
using Google.Protobuf.Collections;
using OpenTelemetry.Proto.Common.V1;
using OpenTelemetry.Proto.Metrics.V1;

namespace AspireSessionHost.OTel;

internal enum OTelMetricType
{
    Gauge,
    Sum,
    Histogram,
    Other
}

internal sealed class OTelMetric(string ScopeName, string MetricName, OTelMetricType type, string description, string unit)
{
    internal OTelMetricType Type => type;
    internal string Description => description;
    internal string Unit => unit;

    private ConcurrentDictionary<int, OTelMetricStream> _streams = new();

    internal void AddMetricValue(Metric metric)
    {
        switch (type)
        {
            case OTelMetricType.Gauge:
                AddGaugeDataPoints(metric.Gauge.DataPoints);
                break;
            case OTelMetricType.Sum:
                AddSumDataPoints(metric.Sum.DataPoints);
                break;
            case OTelMetricType.Histogram:
                AddHistogramDataPoints(metric.Histogram.DataPoints);
                break;
            case OTelMetricType.Other:
                break;
        }
    }

    private void AddGaugeDataPoints(RepeatedField<NumberDataPoint> dataPoints)
    {
        foreach (var dataPoint in dataPoints)
        {
            var metricStream = GetOrAddMetricStream(dataPoint.Attributes);
            metricStream.AddNumberDataPoint(dataPoint);
        }
    }

    private void AddSumDataPoints(RepeatedField<NumberDataPoint> dataPoints)
    {
        foreach (var dataPoint in dataPoints)
        {
            var metricStream = GetOrAddMetricStream(dataPoint.Attributes);
            metricStream.AddNumberDataPoint(dataPoint);
        }
    }

    private void AddHistogramDataPoints(RepeatedField<HistogramDataPoint> dataPoints)
    {
        foreach (var dataPoint in dataPoints)
        {
            var metricStream = GetOrAddMetricStream(dataPoint.Attributes);
            metricStream.AddHistogramDataPoint(dataPoint);
        }
    }

    private OTelMetricStream GetOrAddMetricStream(RepeatedField<KeyValue> attributes)
    {
        var hashCode = new HashCode();
        foreach (var attribute in attributes.OrderBy(it => it.Key))
        {
            hashCode.Add(attribute);
        }

        var key = hashCode.ToHashCode();
        if (_streams.TryGetValue(key, out var existingStream))
        {
            return existingStream;
        }

        var newStream = new OTelMetricStream();
        _streams.TryAdd(key, newStream);

        return newStream;
    }
}