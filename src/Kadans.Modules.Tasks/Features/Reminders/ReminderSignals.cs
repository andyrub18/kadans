using System.Threading.Channels;
using Kadans.SharedKernel.Notifications;

namespace Kadans.Modules.Tasks.Features.Reminders;

/// <summary>
/// Accounts whose reminders a person just changed (<c>TasksDbContext</c>'s save). Their apps are told a moment later,
/// so the phones that ring reminders themselves fetch their window again (ARCHITECTURE → "Reminders ring on the phone").
/// </summary>
internal sealed class ReminderSignals
{
    private readonly Channel<string> changed = Channel.CreateUnbounded<string>(new UnboundedChannelOptions { SingleReader = true });

    public void Changed(string userId) => changed.Writer.TryWrite(userId);

    internal ChannelReader<string> Changes => changed.Reader;
}

/// <summary>
/// Sends <see cref="Kind"/> to each changed account: live to its open apps, silently to its phones. Changes are
/// gathered for two seconds, so a burst of edits (a todo created with its occurrences, then moved) is one signal.
/// </summary>
internal sealed class ReminderSignaller(ReminderSignals signals, IServiceScopeFactory scopes, ILogger<ReminderSignaller> logger) : BackgroundService
{
    public const string Kind = "reminders.changed";

    internal static readonly TimeSpan Gather = TimeSpan.FromSeconds(2);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            while (await signals.Changes.WaitToReadAsync(stoppingToken))
            {
                await Task.Delay(Gather, stoppingToken);
                var accounts = new HashSet<string>(StringComparer.Ordinal);
                while (signals.Changes.TryRead(out var userId))
                    accounts.Add(userId);

                await using var scope = scopes.CreateAsyncScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<INotificationDispatcher>();
                foreach (var userId in accounts)
                {
                    try
                    {
                        await dispatcher.SignalAsync(userId, Kind, stoppingToken);
                    }
                    catch (Exception ex) when (ex is not OperationCanceledException)
                    {
                        logger.LogWarning(ex, "Could not tell user {UserId}'s apps their reminders changed", userId);
                    }
                }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
        }
    }
}
