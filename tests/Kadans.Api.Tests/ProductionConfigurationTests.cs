using Kadans.Api;
using Microsoft.Extensions.Configuration;

namespace Kadans.Api.Tests;

public class ProductionConfigurationTests
{
    private static readonly string FirebaseKey = CreateFirebaseKey();

    private static string CreateFirebaseKey()
    {
        var path = Path.Combine(Path.GetTempPath(), $"kadans-firebase-{Guid.NewGuid():N}.json");
        File.WriteAllText(path, "{}");
        return path;
    }

    private static Dictionary<string, string?> Complete() =>
        new()
        {
            ["ConnectionStrings:kadans"] = "Host=db;Database=kadans;Username=kadans;Password=x",
            ["Jwt:Key"] = new string('k', 48),
            ["Jwt:Issuer"] = "Kadans",
            ["Jwt:Audience"] = "Kadans.Clients",
            ["Email:Provider"] = "Resend",
            ["Email:Resend:ApiKey"] = "re_123",
            ["Email:LinkBaseUrl"] = "https://api.kadans.app",
            ["Push:Provider"] = "Fcm",
            ["Push:Firebase:CredentialsFile"] = FirebaseKey,
        };

    private static IReadOnlyList<string> Problems(Action<Dictionary<string, string?>> change)
    {
        var values = Complete();
        change(values);
        return ProductionConfiguration.Problems(new ConfigurationBuilder().AddInMemoryCollection(values).Build());
    }

    [Test]
    public async Task A_complete_configuration_has_nothing_to_say()
    {
        await Assert.That(Problems(_ => { })).IsEmpty();
    }

    [Test]
    [Arguments("ConnectionStrings:kadans")]
    [Arguments("Jwt:Key")]
    [Arguments("Jwt:Issuer")]
    [Arguments("Jwt:Audience")]
    [Arguments("Email:Resend:ApiKey")]
    [Arguments("Email:LinkBaseUrl")]
    [Arguments("Push:Firebase:CredentialsFile")]
    public async Task Each_missing_value_is_named(string key)
    {
        var problems = Problems(values => values.Remove(key));

        await Assert.That(problems.Count).IsEqualTo(1);
        await Assert.That(problems[0]).Contains(key.Split(':')[0]);
    }

    [Test]
    public async Task A_short_signing_key_and_a_plain_http_link_base_are_refused()
    {
        await Assert.That(Problems(v => v["Jwt:Key"] = "too-short").Single()).Contains("32");
        await Assert.That(Problems(v => v["Email:LinkBaseUrl"] = "http://api.kadans.app").Single()).Contains("https");
    }

    [Test]
    public async Task A_firebase_key_path_that_is_not_a_file_is_refused()
    {
        // What Docker leaves behind when the file is missing on the first `docker compose up`.
        var directory = Directory.CreateTempSubdirectory("firebase-admin.json").FullName;

        await Assert.That(Problems(v => v["Push:Firebase:CredentialsFile"] = directory).Single()).Contains("not a file");
        await Assert.That(Problems(v => v["Push:Firebase:CredentialsFile"] = directory + "-missing").Single()).Contains("not a file");
        await Assert.That(Problems(v => v["Push:Firebase:CredentialsJson"] = "{}")).IsEmpty();
    }

    [Test]
    public async Task A_firebase_key_the_process_cannot_read_is_refused()
    {
        if (OperatingSystem.IsWindows())
            return;
        var locked = Path.Combine(Path.GetTempPath(), $"kadans-firebase-locked-{Guid.NewGuid():N}.json");
        File.WriteAllText(locked, "{}");
        File.SetUnixFileMode(locked, UnixFileMode.None);
        try
        {
            // root reads anything: nothing to prove there.
            if (Environment.UserName == "root")
                return;
            await Assert.That(Problems(v => v["Push:Firebase:CredentialsFile"] = locked).Single()).Contains("cannot read");
        }
        finally
        {
            File.Delete(locked);
        }
    }

    [Test]
    public async Task Log_providers_need_no_keys_so_a_first_start_without_email_or_push_is_possible()
    {
        var problems = Problems(values =>
        {
            values["Email:Provider"] = "Log";
            values.Remove("Email:Resend:ApiKey");
            values["Push:Provider"] = "Log";
            values.Remove("Push:Firebase:CredentialsFile");
        });

        await Assert.That(problems).IsEmpty();
    }

    [Test]
    public async Task Everything_missing_is_reported_at_once_not_one_restart_at_a_time()
    {
        var problems = ProductionConfiguration.Problems(new ConfigurationBuilder().Build());

        await Assert.That(problems.Count).IsGreaterThanOrEqualTo(4);
        await Assert.That(() => ProductionConfiguration.ThrowIfIncomplete(new ConfigurationBuilder().Build())).Throws<InvalidOperationException>();
    }

    [Test]
    public async Task Simulated_push_runs_only_in_a_load_test()
    {
        await Assert.That(Problems(v => v["Push:Provider"] = "Simulated").Single()).Contains("load tests only");
        await Assert.That(Problems(v =>
        {
            v["Push:Provider"] = "Simulated";
            v["LoadTest:Enabled"] = "true";
        })).IsEmpty();
    }

    [Test]
    public async Task The_fake_store_never_runs_in_production()
    {
        await Assert.That(Problems(v => v["Billing:FakeStore:Enabled"] = "true").Single()).Contains("Development only");
    }

    [Test]
    public async Task Requiring_subscriptions_takes_the_google_settings()
    {
        var problems = Problems(v => v["Billing:Required"] = "true");

        await Assert.That(problems.Count).IsEqualTo(3);
        await Assert.That(Problems(v =>
        {
            v["Billing:Required"] = "true";
            v["Billing:Google:ServiceAccountFile"] = FirebaseKey; // any readable file stands in for the Play key
            v["Billing:Google:NotificationAudience"] = "https://api.kadansplanning.com/billing/google/notifications";
            v["Billing:Google:NotificationServiceAccount"] = "rtdn-push@kadans-420a7.iam.gserviceaccount.com";
        })).IsEmpty();
    }
}
