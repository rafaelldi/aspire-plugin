using Aspire.V1;
using AspireSessionHost.Generated;
using AspireSessionHost.OTel;
using Grpc.Core;
using JetBrains.Lifetimes;
using JetBrains.Rd.Base;
using JetBrains.Rd.Tasks;
using Microsoft.Extensions.Options;
using Polly;
using Polly.Registry;

namespace AspireSessionHost.Resources;

internal sealed class ResourceService(
    Connection connection,
    DashboardService.DashboardServiceClient client,
    RdResourceManager resourceManager,
    ResiliencePipelineProvider<string> resiliencePipelineProvider,
    IOptions<ResourceServiceOptions> options,
    ILogger<ResourceService> logger
) : IDisposable
{
    private const string ApiKeyHeader = "x-resource-service-api-key";
    private readonly Metadata _headers = [];
    private readonly ResourceServiceOptions _optionValue = options.Value;
    private readonly LifetimeDefinition _lifetimeDef = new();

    private readonly ResiliencePipeline _pipeline =
        resiliencePipelineProvider.GetPipeline(nameof(ResourceService));

    internal void Initialize()
    {
        if (_optionValue.ApiKey is not null)
        {
            _headers.Add(ApiKeyHeader, _optionValue.ApiKey);
        }

        _lifetimeDef.Lifetime.StartAttachedAsync(TaskScheduler.Default, async () => await WatchResources());
    }

    private async Task WatchResources()
    {
        logger.LogInformation("Start resource watching");

        await Task.Delay(TimeSpan.FromSeconds(5), Lifetime.AsyncLocal.Value);

        var retryCout = 1;
        await _pipeline.ExecuteAsync(
            async token => await SendWatchResourcesRequest(retryCout++, token),
            Lifetime.AsyncLocal.Value
        );

        logger.LogInformation("Stop resource watching, lifetime is alive {isAlive}", Lifetime.AsyncLocal.Value.IsAlive);
    }

    private async Task SendWatchResourcesRequest(
        int retryCount,
        CancellationToken ct)
    {
        var request = new WatchResourcesRequest { IsReconnect = retryCount > 1 };
        var response = client.WatchResources(request, headers: _headers, cancellationToken: ct);
        await foreach (var update in response.ResponseStream.ReadAllAsync(ct))
        {
            switch (update.KindCase)
            {
                case WatchResourcesUpdate.KindOneofCase.InitialData:
                    await HandleInitialData(update.InitialData, ct);
                    break;
                case WatchResourcesUpdate.KindOneofCase.Changes:
                    await HandleChanges(update.Changes, ct);
                    break;
            }
        }
    }

    private async Task HandleInitialData(InitialResourceData initialResourceData, CancellationToken ct)
    {
        logger.LogTrace("Handle initial resource data");

        await connection.DoWithModel(model => model.Resources.Clear());

        foreach (var resource in initialResourceData.Resources)
        {
            ct.ThrowIfCancellationRequested();

            var resourceModel = resource.ToModel();
            var resourceWrapper = new ResourceWrapper();
            resourceWrapper.Model.SetValue(resourceModel);
            resourceWrapper.GetMetrics.SetSync((_, _) =>
            {
                var resourceId = resourceWrapper.Model.Value.OTelResourceId;
                return string.IsNullOrEmpty(resourceId) ? [] : resourceManager.GetResourceMetrics(resourceId);
            });

            await connection.DoWithModel(model => { model.Resources.TryAdd(resourceModel.Name, resourceWrapper); });
        }
    }

    private async Task HandleChanges(WatchResourcesChanges watchResourcesChanges, CancellationToken ct)
    {
        logger.LogTrace("Handle resource changes");

        foreach (var change in watchResourcesChanges.Value)
        {
            ct.ThrowIfCancellationRequested();

            if (change is null) continue;

            switch (change.KindCase)
            {
                case WatchResourcesChange.KindOneofCase.Upsert:
                    await UpsertResource(change);
                    break;
                case WatchResourcesChange.KindOneofCase.Delete:
                    await DeleteResource(change);
                    break;
            }
        }
    }

    private async Task UpsertResource(WatchResourcesChange change)
    {
        var resourceModel = change.Upsert.ToModel();
        await connection.DoWithModel(model =>
        {
            if (model.Resources.ContainsKey(resourceModel.Name))
            {
                model.Resources[resourceModel.Name].Model.SetValue(resourceModel);
            }
            else
            {
                var resourceWrapper = new ResourceWrapper();
                resourceWrapper.Model.SetValue(resourceModel);
                model.Resources.TryAdd(resourceModel.Name, resourceWrapper);
            }
        });
    }

    private async Task DeleteResource(WatchResourcesChange change)
    {
        await connection.DoWithModel(model => model.Resources.Remove(change.Delete.ResourceName));
    }

    public void Dispose()
    {
        _lifetimeDef.Dispose();
    }
}