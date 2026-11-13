using System.Buffers.Text;
using System.Security.Cryptography;
using Kadans.Modules.Tasks.Domain;
using Kadans.SharedKernel.Http;

namespace Kadans.Modules.Tasks.Features.CalendarFeed;

/// <summary>
/// The secret in a feed's link: the link's nonce followed by its signature (HMAC-SHA256) under a key derived from the
/// server's secret, in base64url (64 characters, nothing a URL has to escape). The server can write the link again
/// whenever Settings shows it, and tells a forged one from a real one before any database read; the database alone
/// cannot write one.
/// </summary>
internal sealed class CalendarFeedLinks(byte[] key)
{
    /// <summary>What the key is derived for (<c>ServerSecret</c>).</summary>
    public const string KeyPurpose = "Kadans.CalendarFeed.Link";

    public const string FileName = "kadans.ics";

    private const int SignatureSize = HMACSHA256.HashSizeInBytes;
    private const int SecretSize = CalendarFeedLink.NonceSize + SignatureSize;

    // Says what is signed, so a signature made here can never be one made for something else under the same key.
    private static ReadOnlySpan<byte> Label => "calendar-feed/v1/"u8;

    public string SecretOf(ReadOnlySpan<byte> nonce)
    {
        Span<byte> secret = stackalloc byte[SecretSize];
        nonce.CopyTo(secret);
        Sign(nonce, secret[CalendarFeedLink.NonceSize..]);
        return Base64Url.EncodeToString(secret);
    }

    /// <summary>The feed's path on this server: <c>/calendar/feed/{secret}/kadans.ics</c>.</summary>
    public string PathOf(ReadOnlySpan<byte> nonce) => $"{SecretLinks.CalendarFeed}/{SecretOf(nonce)}/{FileName}";

    /// <summary>The nonce a link names, when its signature is ours; false for anything else, whatever it is.</summary>
    public bool TryRead(string? secret, out byte[] nonce)
    {
        nonce = [];
        // Exactly the length a secret has: nothing longer is ever decoded.
        if (secret is null || secret.Length != Base64Url.GetEncodedLength(SecretSize))
            return false;

        Span<byte> bytes = stackalloc byte[SecretSize];
        if (!Base64Url.TryDecodeFromChars(secret, bytes, out var written) || written != SecretSize)
            return false;

        Span<byte> expected = stackalloc byte[SignatureSize];
        Sign(bytes[..CalendarFeedLink.NonceSize], expected);
        if (!CryptographicOperations.FixedTimeEquals(expected, bytes[CalendarFeedLink.NonceSize..]))
            return false;

        nonce = bytes[..CalendarFeedLink.NonceSize].ToArray();
        return true;
    }

    private void Sign(ReadOnlySpan<byte> nonce, Span<byte> signature)
    {
        Span<byte> signed = stackalloc byte[Label.Length + CalendarFeedLink.NonceSize];
        Label.CopyTo(signed);
        nonce.CopyTo(signed[Label.Length..]);
        HMACSHA256.HashData(key, signed, signature);
    }
}
