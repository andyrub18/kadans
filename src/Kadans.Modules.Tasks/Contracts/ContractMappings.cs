using Kadans.Modules.Tasks.Domain;

namespace Kadans.Modules.Tasks.Contracts;

/// <summary>
/// Entity → response mapping. Endpoints never return entities directly, so nothing internal
/// (navigation properties, user records) can leak through an <c>Include</c>.
/// </summary>
internal static class ContractMappings
{
    extension(Todo todo)
    {
        public TodoResponse ToResponse() =>
            new(
                todo.Id,
                todo.Title,
                todo.Description,
                todo.Status,
                todo.NotificationEnabled,
                (int)todo.NotificationLeadTime.TotalMinutes,
                todo.PomodoroTemplateId,
                todo.RecurrenceRule?.ToResponse(),
                [.. todo.Remarks.Select(r => r.ToResponse())],
                todo.CreatedAt,
                todo.UpdatedAt
            );
    }

    extension(RecurrenceRule rule)
    {
        public RecurrenceRuleResponse ToResponse() =>
            new(
                rule.Rrule,
                rule.TimeZoneId,
                rule.StartDate,
                rule.Frequency,
                rule.Interval,
                rule.Count,
                rule.Until,
                rule.IsOneTime,
                rule.Exceptions
            );
    }

    extension(TodoRemark remark)
    {
        public TodoRemarkResponse ToResponse() => new(remark.Remark, remark.CreatedAt, remark.UpdatedAt);
    }

    extension(TodoOccurrence occurrence)
    {
        public TodoOccurrenceResponse ToResponse() =>
            new(
                occurrence.Id,
                occurrence.TodoId,
                occurrence.Todo?.Title ?? string.Empty,
                occurrence.ScheduledAt,
                occurrence.OriginalScheduledAt,
                occurrence.Status,
                occurrence.IsRescheduled,
                occurrence.RescheduleReason,
                occurrence.CompletedAt,
                occurrence.CancelledAt,
                occurrence.CancellationReason,
                occurrence.Remarks,
                IsPreview: false
            );
    }

    extension(Todo todo)
    {
        /// <summary>A not-yet-materialized instance of the rule, for calendars looking past the horizon.</summary>
        public TodoOccurrenceResponse PreviewOccurrence(DateTimeOffset at) =>
            new(
                null,
                todo.Id,
                todo.Title,
                at,
                at,
                OccurrenceStatus.Pending,
                false,
                null,
                null,
                null,
                null,
                null,
                IsPreview: true
            );
    }

    extension(PomodoroTemplate template)
    {
        public PomodoroTemplateResponse ToResponse() =>
            new(
                template.Id,
                template.Name,
                [.. template.Phases.OrderBy(p => p.Order).Select(p => p.ToResponse())],
                template.CreatedAt,
                template.UpdatedAt
            );
    }

    extension(PomodoroTemplatePhase phase)
    {
        public PomodoroPhaseResponse ToResponse() =>
            new(phase.Id, phase.Order, phase.Type, phase.DurationMinutes);
    }

    extension(PomodoroRun run)
    {
        public PomodoroRunResponse ToResponse() =>
            new(
                run.Id,
                run.TodoId,
                run.PomodoroTemplateId,
                run.Status,
                run.CurrentPhaseIndex,
                run.PhaseEndsAt,
                run.PausedRemaining is { } remaining ? (int)remaining.TotalSeconds : null,
                run.AutoAdvance,
                [.. run.Phases.OrderBy(p => p.Order).Select(p => p.ToResponse())],
                run.StartedAt,
                run.PausedAt,
                run.CompletedAt,
                run.UpdatedAt
            );
    }

    extension(PomodoroRunPhase phase)
    {
        public PomodoroRunPhaseResponse ToResponse() =>
            new(
                phase.Id,
                phase.Order,
                phase.Type,
                phase.DurationMinutes,
                phase.StartedAt,
                phase.CompletedAt
            );
    }
}
