namespace Kadans.Modules.Identity.Features.Auth;

/// <summary>
/// The Digital Asset Links file: Android fetches it from this host to learn that the Kadans app may open this host's
/// links itself (the <c>android:autoVerify</c> filter in the app's manifest: the emailed password reset, nothing else).
/// One fingerprint on purpose, the key Google Play signs the app with (Play Console → Protected with Play → Manage Play
/// app signing): the link carries a reset token, so no build signed by another key, debug or upload, may claim it.
/// </summary>
internal static class AppLinks
{
    public const string AndroidPackage = "app.kadans";

    public const string PlayAppSigningSha256 =
        "E4:BD:8A:DF:42:D1:58:F5:41:1B:14:77:1B:10:69:A7:C8:66:17:4F:29:1A:84:F6:55:38:B1:11:3A:A6:11:2E";

    public const string AssetLinksJson = $$"""
        [{
          "relation": ["delegate_permission/common.handle_all_urls"],
          "target": {
            "namespace": "android_app",
            "package_name": "{{AndroidPackage}}",
            "sha256_cert_fingerprints": ["{{PlayAppSigningSha256}}"]
          }
        }]
        """;
}
