using Kadans.Modules.Tasks.Contracts;
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
    }
}
