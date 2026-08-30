using Kadans.Api.DTOs;
using Kadans.SharedKernel.Errors;
using FluentValidation;

namespace Kadans.Api.Validators;

public sealed class CreateRecurrenceRulesValidator : AbstractValidator<CreateRecurrenceRule>
{
    public CreateRecurrenceRulesValidator()
    {
        RuleFor(rule => rule.StartDate)
            .Must(startDate => startDate > DateTimeOffset.UtcNow)
            .WithErrorCode(ErrorTypes.InvalidStartDate.Value)
            .WithMessage("Start date must not be in the past.");

        RuleFor(rule => rule.Interval)
            .GreaterThan(0)
            .WithErrorCode(ErrorTypes.InvalidInterval.Value)
            .WithMessage("Interval must be greater than zero.");

        RuleFor(rule => rule.ByHour)
            .Must(byHour => byHour is null || byHour.Count > 0)
            .WithErrorCode(ErrorTypes.InvalidHour.Value)
            .WithMessage("ByHour must contain at least one hour if specified.");

        RuleForEach(rule => rule.ByHour ?? new())
            .InclusiveBetween(0, 23)
            .WithErrorCode(ErrorTypes.InvalidHour.Value)
            .WithMessage("Hour must be between 0 and 23.");

        RuleFor(rule => rule.ByMinute)
            .Must(byMinute => byMinute is null || byMinute.Count > 0)
            .WithErrorCode(ErrorTypes.InvalidMinute.Value)
            .WithMessage("ByMinute must contain at least one minute if specified.");

        RuleForEach(rule => rule.ByMinute ?? new())
            .InclusiveBetween(0, 59)
            .WithErrorCode(ErrorTypes.InvalidMinute.Value)
            .WithMessage("Minute must be between 0 and 59.");

        RuleFor(rule => rule.ByMonthDay)
            .Must(byMonthDay => byMonthDay is null || byMonthDay.Count > 0)
            .WithErrorCode(ErrorTypes.InvalidDayOfMonth.Value)
            .WithMessage("ByMonthDay must contain at least one day if specified.");

        RuleForEach(rule => rule.ByMonthDay ?? new())
            .Must(day => day is (>= -31 and <= -1) or (>= 1 and <= 31))
            .WithErrorCode(ErrorTypes.InvalidDayOfMonth.Value)
            .WithMessage("Day of month must be between 1 and 31, or -1 and -31 to count from the end.");

        RuleFor(rule => rule.ByMonth)
            .Must(byMonth => byMonth is null || byMonth.Count > 0)
            .WithErrorCode(ErrorTypes.InvalidMonth.Value)
            .WithMessage("ByMonth must contain at least one month if specified.");

        RuleForEach(rule => rule.ByMonth ?? new())
            .InclusiveBetween(1, 12)
            .WithErrorCode(ErrorTypes.InvalidMonth.Value)
            .WithMessage("Month must be between 1 and 12.");

        RuleFor(rule => rule.BySetPos)
            .Must(bySetPos => bySetPos is null || bySetPos.Count > 0)
            .WithErrorCode(ErrorTypes.PossibleInvalidSetPos.Value)
            .WithMessage("BySetPos must contain at least one position if specified.");

        RuleForEach(rule => rule.BySetPos ?? new())
            .Must(pos => pos is (>= -366 and <= -1) or (>= 1 and <= 366))
            .WithErrorCode(ErrorTypes.PossibleInvalidSetPos.Value)
            .WithMessage("Set position must be non-zero, between -366 and 366.");

        RuleFor(rule => rule.TimeZone)
            .Must(tz => tz is null || TimeZoneInfo.TryFindSystemTimeZoneById(tz, out _))
            .WithErrorCode(ErrorTypes.InvalidTimeZone.Value)
            .WithMessage("TimeZone must be a valid IANA time zone id (e.g. America/Port-au-Prince).");

        RuleFor(rule => rule.ByDayOfWeek)
            .Must(byDayOfWeek => byDayOfWeek is null || byDayOfWeek.Count > 0)
            .WithErrorCode(ErrorTypes.InvalidRecurrenceRule.Value)
            .WithMessage("ByDayOfWeek must contain at least one day if specified.");

        RuleFor(rule => rule.Count)
            .Must(count => count is null or > 0)
            .WithErrorCode(ErrorTypes.InvalidRecurrenceRule.Value)
            .WithMessage("Count must be greater than zero if specified.");

        RuleFor(rule => rule.Until)
            .Must((rule, until) => until is null || until > rule.StartDate)
            .WithErrorCode(ErrorTypes.InvalidRecurrenceRule.Value)
            .WithMessage("Until date must be after start date if specified.");

        RuleFor(rule => rule)
            .Must(rule => rule.Count is null || rule.Until is null)
            .WithErrorCode(ErrorTypes.InvalidRecurrenceRule.Value)
            .WithMessage("Either Count or Until can be specified, but not both.");
    }
}
