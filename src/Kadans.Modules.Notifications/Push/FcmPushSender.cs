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

    public async Task<IReadOnlyList<string>> SendAsync(IReadOnlyList<PushTarget> targets, NotificationMessage message, CancellationToken cancellationToken = default)
    {
        if (targets.Count == 0)
            return [];

        var data = (message.Data ?? new Dictionary<string, string>())
            .Concat([new KeyValuePair<string, string>("kind", message.Kind)])
            .ToDictionary(kv => kv.Key, kv => kv.Value);

        // One message per registration token. FirebaseAdmin 3.6 marks Token obsolete in favour of
        // Firebase installation ids (Fid), but the FCM client SDKs still hand apps registration tokens;
        // switch to Fid once the clients register installation ids instead.
#pragma warning disable CS0618
        var messages = targets
            .Select(t => new Message
            {
                Token = t.Token,
                Notification = new Notification { Title = message.Title, Body = message.Body },
                Data = data,
            })
            .ToList();
#pragma warning restore CS0618

        var response = await messaging.SendEachAsync(messages, cancellationToken);

        var dead = new List<string>();
        for (var i = 0; i < response.Responses.Count; i++)
        {
            var result = response.Responses[i];
            if (result.IsSuccess)
                continue;

            var code = result.Exception?.MessagingErrorCode;
            if (code is MessagingErrorCode.Unregistered or MessagingErrorCode.InvalidArgument)
                dead.Add(targets[i].Token);
            else
                logger.LogWarning(result.Exception, "FCM send failed for a {Platform} device ({Code})", targets[i].Platform, code);
        }

        logger.LogInformation("FCM: {Success} sent, {Failed} failed, {Dead} dead token(s)", response.SuccessCount, response.FailureCount, dead.Count);
        return dead;
    }
}
