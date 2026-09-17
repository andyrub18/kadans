using Kadans.Api;
using Microsoft.Extensions.Configuration;

namespace Kadans.Api.Tests;

public class ProductionConfigurationTests
{
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
            ["Push:Firebase:CredentialsFile"] = "/run/secrets/firebase-admin.json",
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
}
