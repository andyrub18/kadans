namespace Kadans.SharedKernel.Realtime;

/// <summary>
/// A person's data changed: on another device, through a notification's button, or by a job. Their open apps hear
/// <see cref="LiveAreas.Event"/> a moment later, with the areas that changed, and load what their screens show again.
/// Live only, nothing stored or pushed: an app that was not connected loads afresh when it comes back to the front.
/// </summary>
public interface ILiveChanges
{
    void Changed(string userId, string area);
}

public static class LiveAreas
{
    /// <summary>The hub event, <c>{ "areas": ["budget", "todos"] }</c>.</summary>
    public const string Event = "data.changed";

    /// <summary>Todos, their occurrences and rules, focus templates (a session has its own event).</summary>
    public const string Todos = "todos";

    /// <summary>Accounts, categories and limits, movements, recurring rules, base currency and rates.</summary>
    public const string Budget = "budget";
}
