using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;
using Microsoft.AspNetCore.DataProtection.XmlEncryption;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// Encrypts the Data Protection key ring before it reaches the database: AES-GCM under a key derived from
/// <c>Jwt:Key</c>. A database dump alone (the backups copied off the server) then cannot forge the tokens in
/// emailed links. Changing <c>Jwt:Key</c> invalidates outstanding links, as it already ends every session.
/// </summary>
internal static class KeyRingEncryption
{
    internal const int NonceSize = 12;
    internal const int TagSize = 16;

    public static byte[] DeriveKey(string jwtKey) =>
        HKDF.DeriveKey(
            HashAlgorithmName.SHA256,
            Encoding.UTF8.GetBytes(jwtKey),
            outputLength: 32,
            info: "Kadans.DataProtection.KeyRing"u8.ToArray()
        );

    public static byte[] DeriveKey(IConfiguration configuration) =>
        DeriveKey(configuration["Jwt:Key"] ?? throw new InvalidOperationException("Jwt:Key is missing."));
}

internal sealed class KeyRingXmlEncryptor(byte[] key) : IXmlEncryptor
{
    public EncryptedXmlInfo Encrypt(XElement plaintextElement)
    {
        var plaintext = Encoding.UTF8.GetBytes(plaintextElement.ToString(SaveOptions.DisableFormatting));
        // nonce | tag | ciphertext
        var box = new byte[KeyRingEncryption.NonceSize + KeyRingEncryption.TagSize + plaintext.Length];
        var nonce = box.AsSpan(0, KeyRingEncryption.NonceSize);
        RandomNumberGenerator.Fill(nonce);
        using (var aes = new AesGcm(key, KeyRingEncryption.TagSize))
        {
            aes.Encrypt(
                nonce,
                plaintext,
                box.AsSpan(KeyRingEncryption.NonceSize + KeyRingEncryption.TagSize),
                box.AsSpan(KeyRingEncryption.NonceSize, KeyRingEncryption.TagSize)
            );
        }

        var element = new XElement(
            "encryptedKey",
            new XComment(" AES-GCM, key derived from Jwt:Key "),
            new XElement("value", Convert.ToBase64String(box))
        );
        return new EncryptedXmlInfo(element, typeof(KeyRingXmlDecryptor));
    }
}

internal sealed class KeyRingXmlDecryptor : IXmlDecryptor
{
    private readonly byte[] key;

    /// <summary>Data Protection creates decryptors itself, by type name, through this constructor.</summary>
    public KeyRingXmlDecryptor(IServiceProvider services)
        : this(KeyRingEncryption.DeriveKey(services.GetRequiredService<IConfiguration>())) { }

    internal KeyRingXmlDecryptor(byte[] key) => this.key = key;

    public XElement Decrypt(XElement encryptedElement)
    {
        var box = Convert.FromBase64String(
            (string?)encryptedElement.Element("value") ?? throw new CryptographicException("The encrypted key has no value.")
        );
        if (box.Length < KeyRingEncryption.NonceSize + KeyRingEncryption.TagSize)
            throw new CryptographicException("The encrypted key is truncated.");

        var plaintext = new byte[box.Length - KeyRingEncryption.NonceSize - KeyRingEncryption.TagSize];
        using (var aes = new AesGcm(key, KeyRingEncryption.TagSize))
        {
            aes.Decrypt(
                box.AsSpan(0, KeyRingEncryption.NonceSize),
                box.AsSpan(KeyRingEncryption.NonceSize + KeyRingEncryption.TagSize),
                box.AsSpan(KeyRingEncryption.NonceSize, KeyRingEncryption.TagSize),
                plaintext
            );
        }
        return XElement.Parse(Encoding.UTF8.GetString(plaintext));
    }
}
