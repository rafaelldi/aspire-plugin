using AspireSessionHost.Generated;

// ReSharper disable ReplaceAsyncWithTaskReturn

namespace AspireSessionHost.Sessions;

internal sealed class SessionService(Connection connection, ILogger<SessionService> logger)
{
    internal async Task<SessionUpsertResult?> Upsert(Session session)
    {
        var envs = session.Env
            ?.Where(it => it.Value is not null)
            ?.Select(it => new SessionEnvironmentVariable(it.Name, it.Value!))
            ?.ToArray();
        var sessionModel = new SessionModel(
            session.ProjectPath,
            session.Debug ?? false,
            session.LaunchProfile,
            session.DisableLaunchProfile ?? false,
            session.Args,
            envs
        );
        logger.LogInformation("Starting a new session {session}", sessionModel);

        return await connection.DoWithModel(model => model.UpsertSession.Sync(sessionModel));
    }

    internal async Task<bool> Delete(string id)
    {
        logger.LogInformation("Deleting the new session {sessionId}", id);

        return await connection.DoWithModel(model => model.DeleteSession.Sync(id));
    }
}