using Kadans.Api.Models;

namespace Kadans.Api.Contracts;

/// <summary>
/// Entity → response mapping. Endpoints never return entities directly, so nothing internal
/// (navigation properties, user records) can leak through an <c>Include</c>.
/// </summary>
public static class ContractMappings
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
                occurrence.OccurrenceDate,
                occurrence.IsCompleted,
                occurrence.CompletedAt,
                occurrence.IsCancelled,
                occurrence.CancellationReason,
                occurrence.Remarks
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
