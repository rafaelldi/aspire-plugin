using Grpc.Core;
using OpenTelemetry.Proto.Collector.Metrics.V1;

namespace AspireSessionHost.OTel;

internal sealed class OTelMetricService(
    MetricsService.MetricsServiceClient client,
    MetricService metricService
) : MetricsService.MetricsServiceBase
{
    public override async Task<ExportMetricsServiceResponse> Export(
        ExportMetricsServiceRequest request,
        ServerCallContext context)
    {
        metricService.Send(request);

//        foreach (var resourceMetric in request.ResourceMetrics)
//        {
//            var serviceName = resourceMetric.Resource.GetServiceName();
//            if (serviceName is null) continue;
//            ReportScopeMetrics(serviceName, resourceMetric.ScopeMetrics);
//        }

        return await client.ExportAsync(request, context.RequestHeaders, context.Deadline, context.CancellationToken);
    }

//    private void ReportScopeMetrics(string serviceName, RepeatedField<ScopeMetrics> scopeMetrics)
//    {
//        foreach (var scopeMetric in scopeMetrics)
//        {
//            if (scopeMetric.Scope.Name is null) continue;
//            ReportMetrics(serviceName, scopeMetric.Scope.Name, scopeMetric.Metrics);
//        }
//    }
//
//    private void ReportMetrics(string serviceName, string scopeName, RepeatedField<Metric> metrics)
//    {
//        foreach (var metric in metrics)
//        {
//            logger.LogTrace("Metric received {otelMetric}", metric);
//
//            var name = metric.Name;
//            if (name is null) continue;
//            var description = metric.Description;
//            var unit = metric.Unit;
//
//            switch (metric.DataCase)
//            {
//                case Metric.DataOneofCase.Gauge:
//                    ReportDataPoints(serviceName, scopeName, name, description, unit, metric.Gauge.DataPoints);
//                    break;
//                case Metric.DataOneofCase.Sum:
//                    ReportDataPoints(serviceName, scopeName, name, description, unit, metric.Sum.DataPoints);
//                    break;
//                case Metric.DataOneofCase.Histogram:
//                    break;
//            }
//        }
//    }
//
//    private void ReportDataPoints(
//        string serviceName,
//        string scopeName,
//        string name,
//        string description,
//        string unit,
//        RepeatedField<NumberDataPoint> dataPoints)
//    {
//        foreach (var dataPoint in dataPoints)
//        {
//            ReportValue(serviceName, scopeName, name, description, unit, dataPoint);
//        }
//    }
//
//    private void ReportValue(
//        string serviceName,
//        string scopeName,
//        string metricName,
//        string description,
//        string unit,
//        NumberDataPoint dataPoint)
//    {
//        var timestamp = (long)(dataPoint.TimeUnixNano / 1_000_000_000);
//        var sessionMetric = dataPoint.ValueCase switch
//        {
//            NumberDataPoint.ValueOneofCase.AsDouble => new OTelMetric(
//                serviceName,
//                scopeName,
//                metricName,
//                description,
//                unit,
//                dataPoint.AsDouble,
//                timestamp
//            ),
//            NumberDataPoint.ValueOneofCase.AsInt => new OTelMetric(
//                serviceName,
//                scopeName,
//                metricName,
//                description,
//                unit,
//                dataPoint.AsInt,
//                timestamp
//            ),
//            _ => null
//        };
//
//        if (sessionMetric is null) return;
//
//        logger.LogTrace("Reporting a new metric {metric}", sessionMetric);
//        metricService.ReportMetric(sessionMetric);
//    }
}
