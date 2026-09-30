using System.Security.Cryptography;
using System.Xml.Linq;
using Kadans.Modules.Identity.Security;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.DataProtection.KeyManagement;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Identity.Tests;

public class KeyRingEncryptionTests
{
    private static readonly string JwtKey = new('k', 48);

    [Test]
    public async Task A_key_element_comes_back_intact_and_is_unreadable_in_between()
    {
        var key = KeyRingEncryption.DeriveKey(JwtKey);
        var secret = new XElement("masterKey", new XElement("value", "c2VjcmV0LW1hc3Rlci1rZXk="));

        var encrypted = new KeyRingXmlEncryptor(key).Encrypt(secret);

        await Assert.That(encrypted.EncryptedElement.ToString()).DoesNotContain("c2VjcmV0LW1hc3Rlci1rZXk=");
        await Assert.That(encrypted.DecryptorType).IsEqualTo(typeof(KeyRingXmlDecryptor));
        var decrypted = new KeyRingXmlDecryptor(key).Decrypt(encrypted.EncryptedElement);
        await Assert.That(XNode.DeepEquals(decrypted, secret)).IsTrue();
    }

    [Test]
    public async Task Another_jwt_key_cannot_read_it()
    {
        var encrypted = new KeyRingXmlEncryptor(KeyRingEncryption.DeriveKey(JwtKey)).Encrypt(new XElement("masterKey"));
        var other = new KeyRingXmlDecryptor(KeyRingEncryption.DeriveKey(new string('x', 48)));

        await Assert.That(() => other.Decrypt(encrypted.EncryptedElement)).Throws<CryptographicException>();
    }

    [Test]
    public async Task A_token_protected_before_a_restart_is_readable_after_it()
    {
        // Same shape as production (keys persisted and encrypted, decryptor recreated by type name on load),
        // with a folder standing in for the database.
        var folder = Directory.CreateTempSubdirectory("kadans-keys");
        try
        {
            var token = Provider(folder).CreateProtector("EmailConfirmation").Protect("user-1");

            var stored = await File.ReadAllTextAsync(Directory.GetFiles(folder.FullName).Single());
            await Assert.That(stored).Contains("encryptedKey");
            await Assert.That(stored).DoesNotContain("<masterKey");

            // A fresh container: new process, same database, same Jwt:Key.
            await Assert.That(Provider(folder).CreateProtector("EmailConfirmation").Unprotect(token)).IsEqualTo("user-1");
        }
        finally
        {
            folder.Delete(recursive: true);
        }
    }

    private static IDataProtectionProvider Provider(DirectoryInfo folder)
    {
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?> { ["Jwt:Key"] = JwtKey })
            .Build();
        var services = new ServiceCollection();
        services.AddSingleton<IConfiguration>(configuration);
        services.AddDataProtection().SetApplicationName("Kadans").PersistKeysToFileSystem(folder);
        services
            .AddOptions<KeyManagementOptions>()
            .Configure<IConfiguration>((options, config) =>
                options.XmlEncryptor = new KeyRingXmlEncryptor(KeyRingEncryption.DeriveKey(config))
            );
        return services.BuildServiceProvider().GetRequiredService<IDataProtectionProvider>();
    }
}
