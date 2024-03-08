using JetBrains.Annotations;

namespace AspireSessionHost;

[UsedImplicitly]
internal sealed record Session(
    LaunchConfiguration[] LaunchConfigurations,
    EnvironmentVariable[]? Env,
    string[]? Args
);

[UsedImplicitly]
internal sealed record LaunchConfiguration(
    string Type,
    string ProjectPath,
    Mode? Mode,
    string[]? LaunchProfile,
    bool? DisableLaunchProfile
);

internal enum Mode
{
    Debug,
    NoDebug
}

[UsedImplicitly]
internal sealed record EnvironmentVariable(string Name, string? Value);

[UsedImplicitly]
internal sealed record ErrorResponse(ErrorDetail Error);

[UsedImplicitly]
internal sealed record ErrorDetail(
    string Code,
    string Message,
    ErrorDetail[]? Details = null
);