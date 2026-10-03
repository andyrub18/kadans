namespace Kadans.Modules.Tasks.Features;

/// <summary>Reminder notification wording ({0} = local time, {1} = duration text).</summary>
internal sealed record ReminderTexts(
    string StartsAtFormat,
    string StartsNowFormat,
    string MinuteAbbrev,
    string HourAbbrev,
    string DayAbbrev
);

/// <summary>
/// Pomodoro wording ({0} = minutes; lap prefix uses {0} = lap number). The time's-up texts are for manual runs, which
/// wait for the person at the end of each phase; they name what comes next. Finished: {0} = the local end time.
/// </summary>
internal sealed record PomodoroTexts(
    string BreakFormat,
    string FocusFormat,
    string Complete,
    string LapFormat,
    string TimeUpNextFocusFormat,
    string TimeUpNextBreakFormat,
    string TimeUpLast,
    string FinishedFormat
);

internal static class LocalizedTexts
{
    public static ReminderTexts Reminder(string? language) =>
        language switch
        {
            "fr" => new("Commence à {0} — dans {1}", "Commence maintenant ({0})", "min", "h", "j"),
            "ht" => new("L ap kòmanse a {0} — nan {1}", "L ap kòmanse kounye a ({0})", "min", "è", "jou"),
            _ => new("Starts at {0} — in {1}", "Starts now ({0})", "min", "h", "d"),
        };

    public static PomodoroTexts Pomodoro(string? language) =>
        language switch
        {
            "fr" => new(
                "Pause — {0} min", "Concentration — {0} min", "Pomodoro terminé. Bravo !", "Tour {0} · ",
                "Temps écoulé. Ensuite : {0} min de concentration, quand vous voulez.",
                "Temps écoulé. Ensuite : {0} min de pause, quand vous voulez.",
                "Temps écoulé. C'était la dernière phase : terminez quand vous voulez.",
                "La session s'est terminée à {0}, comme prévu."
            ),
            "ht" => new(
                "Poz — {0} min", "Konsantrasyon — {0} min", "Pomodoro fini. Bèl travay!", "Tou {0} · ",
                "Tan an fini. Apre sa: {0} min konsantrasyon, lè ou vle.",
                "Tan an fini. Apre sa: {0} min poz, lè ou vle.",
                "Tan an fini. Se te dènye etap la: fini lè ou vle.",
                "Seyans lan fini a {0}, jan sa te prevwa."
            ),
            _ => new(
                "Break — {0} min", "Focus — {0} min", "Pomodoro complete. Well done!", "Lap {0} · ",
                "Time's up. Next: {0} min of focus, when you're ready.",
                "Time's up. Next: a {0}-minute break, when you're ready.",
                "Time's up. That was the last phase: finish when you're ready.",
                "The session ended at {0}, as planned."
            ),
        };
}
