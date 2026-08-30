using Kadans.Modules.Tasks.Domain;

namespace Kadans.Modules.Tasks.Contracts;

public sealed record CreatePomodoroTemplate(string Name, List<CreatePomodoroPhase> Phases);

public sealed record CreatePomodoroPhase(PomodoroPhaseType Type, int DurationMinutes);

public sealed record AdvancePomodoroRun(int? ExpectedPhaseIndex = null);
