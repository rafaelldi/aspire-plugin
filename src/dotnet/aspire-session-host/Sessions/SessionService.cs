using AspireSessionHost.Generated;

// ReSharper disable ReplaceAsyncWithTaskReturn

namespace AspireSessionHost.Sessions;

internal sealed class SessionService(Connection connection, ILogger<SessionService> logger)
{
    internal async Task<Guid?> Create(Session session)
    {
        var id = Guid.NewGuid();
        var stringId = id.ToString();
        var envs = session.Env
            ?.Where(it => it.Value is not null)
            ?.Select(it => new SessionEnvironmentVariable(it.Name, it.Value!))
            ?.ToArray();
        var sessionModel = new SessionModel(
            stringId,
            session.ProjectPath,
            session.Debug ?? false,
            session.LaunchProfile,
            session.DisableLaunchProfile ?? false,
            session.Args,
            envs
        );
        logger.LogInformation("Starting a new session {session}", sessionModel);

        var result = await connection.DoWithModel(model => model.Sessions.TryAdd(stringId, sessionModel));

        return result ? id : null;
    }

    internal async Task<bool> Delete(Guid id)
    {
        logger.LogInformation("Deleting the new session {sessionId}", id);

        return await connection.DoWithModel(model => model.Sessions.Remove(id.ToString()));
    }
}