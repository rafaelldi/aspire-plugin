using Google.Protobuf.Collections;
using OpenTelemetry.Proto.Metrics.V1;

namespace AspireSessionHost.OTel;

internal readonly record struct RdMetricId(string ScopeName, string MetricName);

internal enum RdMetricType
{
    Gauge,
    Sum,
    Histogram,
    Other
}

internal sealed class RdMetric(RdMetricId id, RdMetricType type, string description, string unit)
{
    internal RdMetricType Type => type;

    internal void AddMetricValue(Metric metric)
    {
        switch (type)
        {
            case RdMetricType.Gauge:
                AddGaugeDataPoints(metric.Gauge.DataPoints);

                break;
            case RdMetricType.Sum:
                AddSumDataPoints(metric.Sum.DataPoints);

                break;
            case RdMetricType.Histogram:
                AddHistogramDataPoints(metric.Histogram.DataPoints);

                break;
            case RdMetricType.Other:
                break;
        }
    }

    private void AddGaugeDataPoints(RepeatedField<NumberDataPoint> dataPoints)
    {

    }

    private void AddSumDataPoints(RepeatedField<NumberDataPoint> dataPoints)
    {
    }

    private void AddHistogramDataPoints(RepeatedField<HistogramDataPoint> dataPoints)
    {
    }
}