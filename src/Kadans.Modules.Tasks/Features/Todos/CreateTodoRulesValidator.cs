using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Errors;
using FluentValidation;

namespace Kadans.Modules.Tasks.Features.Todos;

internal sealed class CreateOneTimeTodoRulesValidator : AbstractValidator<CreateOneTimeTodo>
{
    public CreateOneTimeTodoRulesValidator()
    {
        RuleFor(request => request.Title)
            .NotEmpty()
            .WithMessage("Title is required.")
            .WithErrorCode(ErrorTypes.TitleRequired.Value);

        RuleFor(request => request.DueDate)
            .Must(dueDate => dueDate > DateTimeOffset.UtcNow)
            .WithMessage("Due date must be in the future.")
            .WithErrorCode(ErrorTypes.InvalidDueDate.Value);

        RuleFor(request => request.NotifyBeforeInMinutes)
            .LessThanOrEqualTo(Todo.MaxNotifyBeforeMinutes)
            .WithMessage("A reminder can come at most 30 days before the start.")
            .WithErrorCode(ErrorTypes.InvalidNotifyBefore.Value);
    }
}

internal sealed class CreateRecurringTodoRulesValidator : AbstractValidator<CreateRecurringTodo>
{
    public CreateRecurringTodoRulesValidator()
    {
        RuleFor(request => request.Title)
            .NotEmpty()
            .WithMessage("Title is required.")
            .WithErrorCode(ErrorTypes.TitleRequired.Value);

        RuleFor(request => request.RecurrenceRule)
            .SetValidator(new CreateRecurrenceRulesValidator());

        RuleFor(request => request.NotifyBeforeInMinutes)
            .LessThanOrEqualTo(Todo.MaxNotifyBeforeMinutes)
            .WithMessage("A reminder can come at most 30 days before the start.")
            .WithErrorCode(ErrorTypes.InvalidNotifyBefore.Value);
    }
}

/// <summary>The todo's own fields; a new recurrence rule is validated on its own (<see cref="CreateRecurrenceRulesValidator"/>).</summary>
internal sealed class UpdateTodoRulesValidator : AbstractValidator<UpdateTodo>
{
    public UpdateTodoRulesValidator()
    {
        RuleFor(request => request.Title)
            .NotEmpty()
            .WithMessage("Title is required.")
            .WithErrorCode(ErrorTypes.TitleRequired.Value);

        RuleFor(request => request.NotifyBeforeInMinutes)
            .LessThanOrEqualTo(Todo.MaxNotifyBeforeMinutes)
            .WithMessage("A reminder can come at most 30 days before the start.")
            .WithErrorCode(ErrorTypes.InvalidNotifyBefore.Value);
    }
}
