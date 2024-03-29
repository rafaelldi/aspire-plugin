namespace AspireSessionHost.OTel;

internal record OTelMetric(
    string ServiceName,
    string ScopeName,
    string MetricName,
    string? Description,
    string? Unit,
    double Value,
    long Timestamp
);