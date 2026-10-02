using Kadans.Modules.Tasks.Domain;

namespace Kadans.Modules.Tasks.Contracts;

public sealed record CreatePomodoroTemplate(string Name, List<CreatePomodoroPhase> Phases);

public sealed record CreatePomodoroPhase(PomodoroPhaseType Type, int DurationMinutes);

/// <param name="ExpectedPhaseIndex">The phase the caller sees; a different one means someone else moved first.</param>
/// <param name="OnlyIfEnded">
/// A watching app saw a hands-free phase run out: advance only if the server's clock agrees (else the run comes back
/// unchanged). False is "Next phase": skip now, whatever is left.
/// </param>
public sealed record AdvancePomodoroRun(int? ExpectedPhaseIndex = null, bool OnlyIfEnded = false);
