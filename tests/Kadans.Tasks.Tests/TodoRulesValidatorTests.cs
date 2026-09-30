using Kadans.Modules.Tasks.Contracts;
using Kadans.Modules.Tasks.Domain;
using Kadans.Modules.Tasks.Features.Todos;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Recurrence;

namespace Kadans.Tasks.Tests;

public class TodoRulesValidatorTests
{
    private static readonly DateTimeOffset Tomorrow = DateTimeOffset.UtcNow.AddDays(1);

    [Test]
    public async Task A_thirty_day_lead_is_the_longest_a_one_time_todo_accepts()
    {
        var atLimit = await new CreateOneTimeTodoRulesValidator().ValidateAsync(OneTime(Todo.MaxNotifyBeforeMinutes));
        var beyond = await new CreateOneTimeTodoRulesValidator().ValidateAsync(OneTime(Todo.MaxNotifyBeforeMinutes + 1));

        await Assert.That(atLimit.IsValid).IsTrue();
        await Assert.That(beyond.Errors.Select(e => e.ErrorCode)).IsEquivalentTo([ErrorTypes.InvalidNotifyBefore.Value]);
    }

    [Test]
    public async Task A_recurring_todo_has_the_same_lead_limit()
    {
        var rule = new CreateRecurrenceRule(Frequency.Daily, Tomorrow);

        var atLimit = await new CreateRecurringTodoRulesValidator().ValidateAsync(new CreateRecurringTodo("Water", "", true, rule, Todo.MaxNotifyBeforeMinutes));
        var beyond = await new CreateRecurringTodoRulesValidator().ValidateAsync(new CreateRecurringTodo("Water", "", true, rule, uint.MaxValue));

        await Assert.That(atLimit.IsValid).IsTrue();
        await Assert.That(beyond.Errors.Select(e => e.ErrorCode)).IsEquivalentTo([ErrorTypes.InvalidNotifyBefore.Value]);
    }

    [Test]
    public async Task An_update_may_leave_the_lead_out_but_not_exceed_the_limit()
    {
        var untouched = await new UpdateTodoRulesValidator().ValidateAsync(new UpdateTodo("Dentist", "", true));
        var beyond = await new UpdateTodoRulesValidator().ValidateAsync(new UpdateTodo("Dentist", "", true, NotifyBeforeInMinutes: uint.MaxValue));

        await Assert.That(untouched.IsValid).IsTrue();
        await Assert.That(beyond.Errors.Select(e => e.ErrorCode)).IsEquivalentTo([ErrorTypes.InvalidNotifyBefore.Value]);
    }

    [Test]
    public async Task An_update_cannot_blank_the_title()
    {
        var result = await new UpdateTodoRulesValidator().ValidateAsync(new UpdateTodo(" ", "", true));

        await Assert.That(result.Errors.Select(e => e.ErrorCode)).IsEquivalentTo([ErrorTypes.TitleRequired.Value]);
    }

    private static CreateOneTimeTodo OneTime(uint notifyBeforeInMinutes) =>
        new("Dentist", "", true, Tomorrow, notifyBeforeInMinutes);
}
