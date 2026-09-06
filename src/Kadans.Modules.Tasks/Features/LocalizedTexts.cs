namespace Kadans.Modules.Tasks.Features;

/// <summary>Reminder notification wording ({0} = local time, {1} = duration text).</summary>
internal sealed record ReminderTexts(
    string StartsAtFormat,
    string StartsNowFormat,
    string LessThanAMinute,
    string MinuteAbbrev,
    string HourAbbrev,
    string DayAbbrev
);

/// <summary>Pomodoro auto-advance wording ({0} = minutes; lap prefix uses {0} = lap number).</summary>
internal sealed record PomodoroTexts(string BreakFormat, string FocusFormat, string Complete, string LapFormat);

internal static class LocalizedTexts
{
    public static ReminderTexts Reminder(string? language) =>
        language switch
        {
            "fr" => new("Commence à {0} — dans {1}", "Commence maintenant ({0})", "moins d'une minute", "min", "h", "j"),
            "ht" => new("L ap kòmanse a {0} — nan {1}", "L ap kòmanse kounye a ({0})", "mwens pase yon minit", "min", "è", "jou"),
            _ => new("Starts at {0} — in {1}", "Starts now ({0})", "less than a minute", "min", "h", "d"),
        };

    public static PomodoroTexts Pomodoro(string? language) =>
        language switch
        {
            "fr" => new("Pause — {0} min", "Concentration — {0} min", "Pomodoro terminé. Bravo !", "Tour {0} · "),
            "ht" => new("Poz — {0} min", "Konsantrasyon — {0} min", "Pomodoro fini. Bèl travay!", "Tou {0} · "),
            _ => new("Break — {0} min", "Focus — {0} min", "Pomodoro complete. Well done!", "Lap {0} · "),
        };
}
