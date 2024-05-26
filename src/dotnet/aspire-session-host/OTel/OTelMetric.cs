using Google.Protobuf.Collections;
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

    }

    private void AddSumDataPoints(RepeatedField<NumberDataPoint> dataPoints)
    {
    }

    private void AddHistogramDataPoints(RepeatedField<HistogramDataPoint> dataPoints)
    {
    }
}