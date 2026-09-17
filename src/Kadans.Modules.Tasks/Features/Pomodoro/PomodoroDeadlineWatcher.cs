using System.Threading.Channels;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// Wakes <see cref="PomodoroDeadlineWatcher"/> early: a run just started, resumed or advanced, so
/// the next deadline it is sleeping towards may no longer be the nearest one.
/// </summary>
internal sealed class PomodoroDeadlineSignal
{
    // Capacity 1 + DropWrite: any number of pulses while the watcher is busy collapse into one re-check.
    private readonly Channel<bool> pulses = Channel.CreateBounded<bool>(
        new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite }
    );

    public void Pulse() => pulses.Writer.TryWrite(true);

    /// <summary>Returns when pulsed or after <paramref name="max"/>, whichever comes first.</summary>
    public async Task WaitAsync(TimeSpan max, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(max);
        try
        {
            await pulses.Reader.ReadAsync(timeout.Token);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            // slept the full delay: that is the normal way out
        }
    }
}

/// <summary>
/// Hands-free runs change phase on the second, not on a polling grid: the watcher sleeps until the
/// exact moment the nearest phase ends, steps what is due, and sleeps towards the next deadline.
/// It replaced a Quartz job that scanned every 5 s (a phase change could be announced up to 5 s late).
/// <c>Tasks:PomodoroAutoAdvanceSeconds</c> is only the longest it sleeps without re-checking – the net
/// under a missed pulse; everything it does is an idempotent scan, so a restart loses nothing.
/// </summary>
internal sealed class PomodoroDeadlineWatcher(
    IServiceScopeFactory scopes,
    PomodoroDeadlineSignal signal,
    IOptions<TasksOptions> options,
    ILogger<PomodoroDeadlineWatcher> logger
) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var fallback = TimeSpan.FromSeconds(Math.Max(1, options.Value.PomodoroAutoAdvanceSeconds));
        while (!stoppingToken.IsCancellationRequested)
        {
            DateTimeOffset? next = null;
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                next = await scope.ServiceProvider
                    .GetRequiredService<PomodoroAutoAdvancer>()
                    .StepDueRunsAsync(DateTimeOffset.UtcNow, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Pomodoro deadline pass failed; retrying after the fallback delay");
            }

            try
            {
                await signal.WaitAsync(NextDelay(DateTimeOffset.UtcNow, next, fallback), stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    /// <summary>Until the next deadline, never longer than the fallback, never a busy loop.</summary>
    internal static TimeSpan NextDelay(DateTimeOffset now, DateTimeOffset? nextDeadline, TimeSpan fallback)
    {
        var floor = TimeSpan.FromMilliseconds(20);
        if (nextDeadline is null)
            return fallback;

        var until = nextDeadline.Value - now;
        return until < floor ? floor : until > fallback ? fallback : until;
    }
}
