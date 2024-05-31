using AspireSessionHost.Generated;
using OpenTelemetry.Proto.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class OTelMetricStream
{
    private const int Capacity = 300;
    private readonly object _lockObj = new();
    private int _index = -1;
    private readonly ResourceMetricPoint[] _points = new ResourceMetricPoint[Capacity];

    internal void AddNumberDataPoint(NumberDataPoint number)
    {
        var timestamp = (long)(number.TimeUnixNano / 1_000_000_000);
        if (number.ValueCase is NumberDataPoint.ValueOneofCase.AsInt)
        {
            var value = number.AsInt;
            var point = new LongResourceMetricPoint(value, timestamp);
            Put(point);
        }
        else if (number.ValueCase is NumberDataPoint.ValueOneofCase.AsDouble)
        {
            var value = number.AsDouble;
            var point = new DoubleResourceMetricPoint(value, timestamp);
            Put(point);
        }
    }

    internal void AddHistogramDataPoint(HistogramDataPoint histogram)
    {
    }

    private void Put(ResourceMetricPoint point)
    {
        lock (_lockObj)
        {
            if (++_index == Capacity) _index = 0;
            _points[_index] = point;
        }
    }

    internal ResourceMetricPoint? GetCurrentPoint()
    {
        if (_index == -1) return null;

        ResourceMetricPoint point;
        lock (_lockObj)
        {
            point = _points[_index];
        }

        return point;
    }
}