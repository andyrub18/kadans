using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Persistence;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.EntityFrameworkCore;

namespace Kadans.Modules.Tasks.Features.Pomodoro;

/// <summary>
/// A manual run waits at the end of each phase for the person to move on. This tells them once, the moment the
/// phase runs out, what comes next, without advancing (hands-free runs announce their phase changes instead).
/// Driven by <see cref="PomodoroDeadlineWatcher"/>, like the auto-advance.
/// </summary>
internal sealed class PomodoroTimeUp(
    TasksDbContext dbContext,
    INotificationDispatcher dispatcher,
    IUserDirectory users,
    ILogger<PomodoroTimeUp> logger
)
{
    public const string Kind = "pomodoro.phase.ended";

    /// <summary>
    /// A phase that ran out this long ago (the server was down, or the run was left at 0:00 before this existed) is
    /// marked as told without a notification: "time's up" an hour late helps nobody.
    /// </summary>
    internal static readonly TimeSpan StaleAfter = TimeSpan.FromMinutes(15);

    /// <summary>Tells every due run and returns when the next manual phase runs out (null: none is waiting to).</summary>
    public async Task<DateTimeOffset?> NotifyDueRunsAsync(DateTimeOffset now, CancellationToken cancellationToken)
    {
        var dueIds = await Waiting()
            .Where(r => r.PhaseEndsAt <= now)
            .OrderBy(r => r.PhaseEndsAt)
            .Take(100)
            .Select(r => r.Id)
            .ToListAsync(cancellationToken);

        var languages = new Dictionary<string, string>();
        foreach (var runId in dueIds)
        {
            dbContext.ChangeTracker.Clear();
            await NotifyAsync(runId, now, languages, cancellationToken);
        }

        return await Waiting().Where(r => r.PhaseEndsAt > now).MinAsync(r => r.PhaseEndsAt, cancellationToken);
    }

    /// <summary>Active manual runs whose current phase has not said its time is up.</summary>
    private IQueryable<PomodoroRun> Waiting() =>
        dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Where(r =>
                r.Status == PomodoroRunStatus.Active
                && !r.AutoAdvance
                && (r.TimeUpPhaseIndex == null || r.TimeUpPhaseIndex < r.CurrentPhaseIndex)
            );

    private async Task NotifyAsync(Guid runId, DateTimeOffset now, Dictionary<string, string> languages, CancellationToken cancellationToken)
    {
        var run = await dbContext
            .PomodoroRuns.IgnoreQueryFilters()
            .Include(r => r.Phases)
            .Include(r => r.Todo)
            .AsSplitQuery()
            .FirstOrDefaultAsync(r => r.Id == runId, cancellationToken);
        if (run is null || !run.TimeUpDue(now))
            return; // the person moved on between the scan and this load

        run.TimeUpSent();
        try
        {
            await dbContext.SaveChangesAsync(cancellationToken);
        }
        catch (DbUpdateConcurrencyException)
        {
            // The person advanced, paused or ended the run at that very moment: the time's up is moot.
            logger.LogDebug("Run {RunId} changed while its time's up was going out", runId);
            return;
        }

        if (now - run.PhaseEndsAt!.Value > StaleAfter)
            return;

        if (!languages.TryGetValue(run.UserId, out var language))
        {
            language = (await users.FindAsync(run.UserId, cancellationToken))?.Language ?? "en";
            languages[run.UserId] = language;
        }

        await dispatcher.DispatchAsync(
            run.UserId,
            new NotificationMessage(
                Kind,
                run.Todo.Title,
                Body(LocalizedTexts.Pomodoro(language), run),
                new Dictionary<string, string>
                {
                    ["todoId"] = run.TodoId.ToString(),
                    ["runId"] = run.Id.ToString(),
                    ["currentPhaseIndex"] = run.CurrentPhaseIndex.ToString(),
                }
            ),
            cancellationToken
        );
    }

    /// <summary>What comes next: the following phase (the first of the next lap for a looping run), or the end.</summary>
    internal static string Body(PomodoroTexts texts, PomodoroRun run)
    {
        var ordered = run.Phases.OrderBy(p => p.Order).ToList();
        var isLast = run.CurrentPhaseIndex == ordered.Count - 1;
        if (isLast && !run.Loop)
            return texts.TimeUpLast;

        var next = isLast ? ordered[ordered.Count - run.CycleLength] : ordered[run.CurrentPhaseIndex + 1];
        return string.Format(
            next.Type == PomodoroPhaseType.Break ? texts.TimeUpNextBreakFormat : texts.TimeUpNextFocusFormat,
            next.DurationMinutes
        );
    }
}
