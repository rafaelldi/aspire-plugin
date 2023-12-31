﻿using AspireSessionHost.Generated;

// ReSharper disable ReplaceAsyncWithTaskReturn

namespace AspireSessionHost.Sessions;

internal sealed class SessionService(Connection connection, ILogger<SessionService> logger)
{
    private const string TelemetryServiceName = "OTEL_SERVICE_NAME";

    internal async Task<Guid?> Create(Session session)
    {
        var id = Guid.NewGuid();
        var stringId = id.ToString();
        var serviceName = session.Env?.FirstOrDefault(it => it.Name == TelemetryServiceName);
        var envs = session.Env
            ?.Where(it => it.Value is not null)
            ?.Select(it => new EnvironmentVariableModel(it.Name, it.Value!))
            ?.ToArray();
        var sessionModel = new SessionModel(
            stringId,
            session.ProjectPath,
            session.Debug,
            envs,
            session.Args,
            serviceName?.Value
        );
        logger.LogDebug("Starting a new session {session}", sessionModel);

        var result = await connection.DoWithModel(model => model.Sessions.TryAdd(stringId, sessionModel));

        return result ? id : null;
    }

    internal async Task<bool> Delete(Guid id)
    {
        logger.LogDebug("Deleting the new session {sessionId}", id);
        return await connection.DoWithModel(model => model.Sessions.Remove(id.ToString()));
    }
}