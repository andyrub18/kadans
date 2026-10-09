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
    /// One device's message. A reminder lives until its start (delivered later it is only noise) and collapses per
    /// occurrence. To an app that rings reminders itself it is a data message the app shows, so the app can drop one it
    /// already rang (ARCHITECTURE → "Reminders ring on the phone"); to any other it stays a notification the system
    /// shows. A silent signal is data only, at normal priority: nothing is shown.
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
        if (envelope.Silent)
            return new Message
            {
                Token = token,
                Data = data,
                Android = new AndroidConfig { Priority = Priority.Normal, CollapseKey = message.Kind },
            };

        var notification = new Notification { Title = message.Title, Body = message.Body };
        if (message.Reminder is not { } reminder)
            return new Message { Token = token, Notification = notification, Data = data };

        var android = new AndroidConfig
        {
            Priority = Priority.High,
            TimeToLive = reminder.StartsAt > now ? reminder.StartsAt - now : TimeSpan.Zero,
            CollapseKey = reminder.OccurrenceId.ToString(),
        };
        if (envelope.Target.RemindersSyncedAt is not null)
        {
            data["title"] = message.Title;
            data["body"] = message.Body;
            return new Message { Token = token, Data = data, Android = android };
        }

        android.Notification = new AndroidNotification { Tag = reminder.OccurrenceId.ToString() };
        return new Message { Token = token, Notification = notification, Data = data, Android = android };
#pragma warning restore CS0618
    }
}
