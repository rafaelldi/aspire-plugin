using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.Http.HttpResults;

namespace AspireSessionHost.Sessions;

internal static class SessionEndpoints
{
    internal static void MapSessionEndpoints(this IEndpointRouteBuilder routes)
    {
        var group = routes.MapGroup("/run_session");

        group.MapPut(
            "/",
            async Task<Results<Created<Session>, BadRequest<string>>> (Session session, SessionService service) =>
            {
                var result = await service.Upsert(session);
                return result != null
                    ? TypedResults.Created($"/run_session/{result.SessionId}", session)
                    : TypedResults.BadRequest("Unable to create a session");
            });

        group.MapDelete(
            "/{sessionId}",
            async Task<Results<Ok, NoContent>> (string sessionId, SessionService service) =>
            {
                var isSuccessful = await service.Delete(sessionId);
                return isSuccessful ? TypedResults.Ok() : TypedResults.NoContent();
            });

        group.MapGet(
            "/notify",
            async (HttpContext context, SessionEventService service) =>
            {
                if (context.WebSockets.IsWebSocketRequest)
                {
                    using var ws = await context.WebSockets.AcceptWebSocketAsync();
                    await Receive(ws, service.Reader);
                }
                else
                {
                    context.Response.StatusCode = StatusCodes.Status400BadRequest;
                }
            });
    }

    private static async Task Receive(WebSocket webSocket, ChannelReader<ISessionEvent> reader)
    {
        var jsonOptions = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower
        };

        await foreach (var value in reader.ReadAllAsync())
        {
            if (webSocket.State is WebSocketState.Closed or WebSocketState.Aborted)
            {
                break;
            }

            var jsonString = JsonSerializer.Serialize(value, jsonOptions);
            var bytes = Encoding.UTF8.GetBytes(jsonString);
            var arraySegment = new ArraySegment<byte>(bytes, 0, bytes.Length);

            await webSocket.SendAsync(arraySegment,
                WebSocketMessageType.Text,
                true,
                CancellationToken.None
            );
        }
    }
}