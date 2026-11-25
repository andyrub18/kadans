using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;
using Kadans.SharedKernel.Notifications;
using Kadans.SharedKernel.Users;
using Microsoft.Extensions.Options;

namespace Kadans.Modules.Notifications.Push;

/// <summary>Firebase Cloud Messaging (Android and, via APNs, iOS). Registered as a singleton: one FirebaseApp per process.</summary>
internal sealed class FcmPushSender : IPushSender
{
    private readonly FirebaseMessaging messaging;
    private readonly ILogger<FcmPushSender> logger;

    public FcmPushSender(IOptions<PushOptions> options, ILogger<FcmPushSender> logger)
    {
        this.logger = logger;
        var firebase = options.Value.Firebase;

        // FromJson/FromFile are marked obsolete in favour of CredentialFactory; they remain the documented
        // way to load a service-account file and are safe when the JSON comes from a secret store.
#pragma warning disable CS0618
        var credential = !string.IsNullOrWhiteSpace(firebase.CredentialsJson)
            ? GoogleCredential.FromJson(firebase.CredentialsJson)
            : !string.IsNullOrWhiteSpace(firebase.CredentialsFile)
                ? GoogleCredential.FromFile(firebase.CredentialsFile)
                : throw new InvalidOperationException("Push:Provider is Fcm but neither Push:Firebase:CredentialsJson nor Push:Firebase:CredentialsFile is set.");
#pragma warning restore CS0618

        var app = FirebaseApp.DefaultInstance ?? FirebaseApp.Create(new AppOptions { Credential = credential });
        messaging = FirebaseMessaging.GetMessaging(app);
    }

    public async Task<PushOutcome> SendAsync(IReadOnlyList<PushEnvelope> envelopes, CancellationToken cancellationToken = default)
    {
        if (envelopes.Count == 0)
            return PushOutcome.AllSent(0);

        var now = DateTimeOffset.UtcNow;
        var messages = envelopes.Select(e => Build(e, now)).ToList();

        var response = await messaging.SendEachAsync(messages, cancellationToken);

        var dead = new List<string>();
        for (var i = 0; i < response.Responses.Count; i++)
        {
            var result = response.Responses[i];
            if (result.IsSuccess)
                continue;

            var code = result.Exception?.MessagingErrorCode;
            if (code is MessagingErrorCode.Unregistered or MessagingErrorCode.InvalidArgument)
                dead.Add(envelopes[i].Target.Token);
            else
                logger.LogWarning(result.Exception, "FCM send failed for a {Platform} device ({Code})", envelopes[i].Target.Platform, code);
        }

        logger.LogInformation("FCM: {Success} sent, {Failed} failed, {Dead} dead token(s)", response.SuccessCount, response.FailureCount, dead.Count);
        return new PushOutcome(response.SuccessCount, response.FailureCount - dead.Count, dead);
    }

    /// <summary>
    /// The Android app's notification channels (its <c>KadansChannels</c>; docs/ARCHITECTURE.md → "Sounds"). A channel
    /// carries its sound, and Android lets nobody but the person change it: so a reminder the system shows goes in the
    /// channel the phone said it rings in. The first is the only one an app from before the choice has.
    /// </summary>
    internal const string RemindersChannel = "kadans.reminders";
    internal const string RemindersChimeChannel = "kadans.reminders.chime";
    internal const string RemindersAlarmChannel = "kadans.reminders.alarm";
    internal const string FocusOverChannel = "kadans.focus.over";
    internal const string BreakOverChannel = "kadans.break.over";

    internal static string RemindersChannelFor(PushTarget target) => target.AlarmReminders switch
    {
        true => RemindersAlarmChannel,
        false => RemindersChimeChannel,
        null => RemindersChannel,
    };

    /// <summary>
    /// How long a focus session's notice is worth delivering to a phone that was out of reach: a phase's end heard much
    /// later is only noise (the server itself says nothing of one older than this: <c>PomodoroTimeUp.StaleAfter</c>).
    /// </summary>
    internal static readonly TimeSpan SessionNoticeUsefulFor = TimeSpan.FromMinutes(15);

    /// <summary>
    /// The channel that has the sound a notification asks for (a focus session's notice: a phase's end, its time up,
    /// the session over), on a phone whose app has it: one that said how its reminders ring is recent enough. Null: no
    /// such sound, or an app from before the sounds.
    /// </summary>
    internal static string? SoundChannelFor(PushTarget target, IReadOnlyDictionary<string, string> data)
    {
        if (target.AlarmReminders is null || !data.TryGetValue(NotificationSounds.DataKey, out var sound))
            return null;
        return sound switch
        {
            NotificationSounds.FocusOver => FocusOverChannel,
            NotificationSounds.BreakOver => BreakOverChannel,
            _ => null,
        };
    }

    /// <summary>
    /// One device's message. A reminder lives as long as it is of use (its start, or ten minutes after a "starts now";
    /// delivered later it is only noise) and collapses per occurrence: the later of its two reminders replaces the first. To an app that rings reminders itself it is a data message the app shows, so the app can drop one it
    /// already rang (ARCHITECTURE → "Reminders ring on the phone"); to any other it stays a notification the system
    /// shows, in the reminders channel. A silent signal is data only, at normal priority: nothing is shown. A message the
    /// app builds itself (<see cref="NotificationMessage.ShownByApp"/>) is data too, to an app that rings reminders.
    /// A focus session's notice (it asks for a sound: <see cref="SoundChannelFor"/>) is data too, to an app that has the
    /// sounds: only a notification the app builds can ring until it is answered, and count as a timer's alarm so that
    /// Kadans' own quiet during focus lets "back to focus" through (ARCHITECTURE → "Sounds"). At high priority, the
    /// session's latest replacing one still waiting, and dropped once too old to be of use. An older app gets the
    /// notification the system shows, as before.
    /// A focus session's change (<see cref="FocusSignal"/>) is data as well: at high priority, and only until the phase
    /// ends, while a focus phase counts down (the phone goes quiet and says so); at normal priority otherwise. The
    /// latest replaces one still waiting for a phone that is offline: the phone reads the sessions as they are then.
    /// </summary>
    internal static Message Build(PushEnvelope envelope, DateTimeOffset now)
    {
        var message = envelope.Message;
        var data = (message.Data ?? new Dictionary<string, string>()).ToDictionary(kv => kv.Key, kv => kv.Value);
        data["kind"] = message.Kind;

        // One message per registration token. FirebaseAdmin 3.6 marks Token obsolete in favour of Firebase
        // installation ids (Fid), but the FCM client SDKs still hand apps registration tokens; switch to Fid once the
        // clients register installation ids instead.
#pragma warning disable CS0618
        var token = envelope.Target.Token;
        if (envelope.Focus is { } focus)
        {
            var quiet = focus.QuietUntil is { } until && until > now;
            return new Message
            {
                Token = token,
                Data = data,
                Android = new AndroidConfig
                {
                    Priority = quiet ? Priority.High : Priority.Normal,
                    TimeToLive = quiet ? focus.QuietUntil!.Value - now : FocusSignal.SoundBackUsefulFor,
                    CollapseKey = message.Kind,
                },
            };
        }
        if (envelope.Silent)
            return new Message
            {
                Token = token,
                Data = data,
                Android = new AndroidConfig { Priority = Priority.Normal, CollapseKey = message.Kind },
            };

        var notification = new Notification { Title = message.Title, Body = message.Body };
        if (message.Reminder is null && SoundChannelFor(envelope.Target, data) is not null)
        {
            // A focus session's notice, to an app that builds it: in the channel its sound names, ringing as the
            // person asked.
            data["title"] = message.Title;
            data["body"] = message.Body;
            return new Message
            {
                Token = token,
                Data = data,
                Android = new AndroidConfig
                {
                    Priority = Priority.High,
                    TimeToLive = SessionNoticeUsefulFor,
                    CollapseKey = data.TryGetValue("runId", out var run) ? run : message.Kind,
                },
            };
        }
        if (message.ShownByApp && envelope.Target.RemindersSyncedAt is not null)
        {
            // The app builds it, buttons and all: data, at high priority (shown at once, Doze or not).
            data["title"] = message.Title;
            data["body"] = message.Body;
            return new Message { Token = token, Data = data, Android = new AndroidConfig { Priority = Priority.High } };
        }
        // Any other notification, and a session's notice for an app from before the sounds: shown by the system.
        if (message.Reminder is not { } reminder)
            return new Message { Token = token, Notification = notification, Data = data };

        var android = new AndroidConfig
        {
            Priority = Priority.High,
            TimeToLive = reminder.UsefulUntil > now ? reminder.UsefulUntil - now : TimeSpan.Zero,
            CollapseKey = reminder.OccurrenceId.ToString(),
        };
        if (envelope.Target.RemindersSyncedAt is not null)
        {
            data["title"] = message.Title;
            data["body"] = message.Body;
            return new Message { Token = token, Data = data, Android = android };
        }

        // The app's reminders channel (urgent: heads-up, sound), the one that rings as the phone said its reminders
        // do; an app too old to have it falls back to its default one.
        android.Notification = new AndroidNotification { Tag = reminder.OccurrenceId.ToString(), ChannelId = RemindersChannelFor(envelope.Target) };
        return new Message { Token = token, Notification = notification, Data = data, Android = android };
#pragma warning restore CS0618
    }
}
