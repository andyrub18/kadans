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
    public async Task A_title_and_a_description_are_refused_past_what_their_columns_hold()
    {
        // A text shared from another app can be long: refused with a sentence, where it used to reach the database
        // and come back as a 500.
        var title = new string('t', Todo.MaxTitleLength);
        var description = new string('d', Todo.MaxDescriptionLength);
        var rule = new CreateRecurrenceRule(Frequency.Daily, Tomorrow);

        await Assert.That((await new CreateOneTimeTodoRulesValidator().ValidateAsync(new CreateOneTimeTodo(title, description, true, Tomorrow))).IsValid).IsTrue();
        await Assert.That((await new CreateRecurringTodoRulesValidator().ValidateAsync(new CreateRecurringTodo(title, description, true, rule))).IsValid).IsTrue();
        await Assert.That((await new UpdateTodoRulesValidator().ValidateAsync(new UpdateTodo(title, description, true))).IsValid).IsTrue();

        var oneTime = await new CreateOneTimeTodoRulesValidator().ValidateAsync(new CreateOneTimeTodo(title + "t", description + "d", true, Tomorrow));
        var recurring = await new CreateRecurringTodoRulesValidator().ValidateAsync(new CreateRecurringTodo(title + "t", description, true, rule));
        var update = await new UpdateTodoRulesValidator().ValidateAsync(new UpdateTodo(title, description + "d", true));

        await Assert.That(oneTime.Errors.Select(e => e.ErrorMessage)).IsEquivalentTo(
            ["A title can have at most 500 characters.", "A description can have at most 4,000 characters."]);
        await Assert.That(recurring.Errors.Select(e => e.ErrorCode)).IsEquivalentTo([ErrorTypes.TextTooLong.Value]);
        await Assert.That(update.Errors.Select(e => e.ErrorMessage)).IsEquivalentTo(["A description can have at most 4,000 characters."]);
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
