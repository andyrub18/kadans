using FluentValidation.Results;
using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Errors;
using OneOf;

namespace Kadans.Modules.Tasks.Features.Todos;

internal static class RequestMappings
{
    extension(CreateRecurrenceRule request)
    {
        public OneOf<ApplicationError, RecurrenceRule> ToDomainRule() =>
            RecurrenceRule.Create(
                frequency: request.Frequency,
                startDate: request.StartDate,
                interval: request.Interval,
                byHour: request.ByHour,
                byMinute: request.ByMinute,
                byDayOfWeek: request.ByDayOfWeek,
                byMonthDay: request.ByMonthDay,
                bySetPos: request.BySetPos,
                byMonth: request.ByMonth,
                count: request.Count,
                until: request.Until,
                exceptions: request.Exceptions,
                timeZoneId: request.TimeZone
            );
    }

    extension(ValidationResult result)
    {
        public ValidationError ToValidationError(string message) =>
            new(ErrorTypes.ValidationError, message, result.Errors.ConvertAll(e => (e.ErrorCode!, e.ErrorMessage)));
    }
}
