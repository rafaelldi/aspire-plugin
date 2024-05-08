using AspireSessionHost.Sessions;
using Microsoft.Extensions.Options;
using OpenTelemetry.Proto.Collector.Logs.V1;
using OpenTelemetry.Proto.Collector.Metrics.V1;
using OpenTelemetry.Proto.Collector.Trace.V1;

namespace AspireSessionHost.OTel;

internal static class OTelServiceRegistration
{
    internal static void AddOTelServices(this IServiceCollection services, ConfigurationManager configuration)
    {
        var otelServiceOptions = configuration
            .GetSection(ConfigureOTelServiceOptions.SectionName)
            .Get<OTelServiceOptions>();
        if (otelServiceOptions?.EndpointUrl is null) return;

        if (!Uri.TryCreate(otelServiceOptions.EndpointUrl, UriKind.Absolute, out var otelEndpointUrl)) return;

        services.AddGrpcClient<LogsService.LogsServiceClient>(o => { o.Address = otelEndpointUrl; });
        services.AddGrpcClient<MetricsService.MetricsServiceClient>(o => { o.Address = otelEndpointUrl; });
        services.AddGrpcClient<TraceService.TraceServiceClient>(o => { o.Address = otelEndpointUrl; });

        services.AddSingleton<ResourceMetricService>();
        services.AddSingleton<SessionNodeService>();

        services.AddSingleton<RdMetricService>();
        services.AddSingleton<RdResourceManager>();
    }

    internal static async Task InitializeOTelServices(this IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var options = scope.ServiceProvider.GetRequiredService<IOptions<OTelServiceOptions>>().Value;
        if (options.EndpointUrl is null) return;

        var sessionMetricService = scope.ServiceProvider.GetRequiredService<ResourceMetricService>();
        await sessionMetricService.Initialize();

        var sessionNodeService = scope.ServiceProvider.GetRequiredService<SessionNodeService>();
        await sessionNodeService.Initialize();

        var rdMetricsService = scope.ServiceProvider.GetRequiredService<RdMetricService>();
        rdMetricsService.Initialize();
    }
}